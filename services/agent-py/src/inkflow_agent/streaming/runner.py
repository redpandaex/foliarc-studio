import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol

import httpx
from redis.asyncio import Redis

from inkflow_agent.config import Settings
from inkflow_agent.streaming.llm_client import (
    LlmConfigurationError,
    LlmStreamChunk,
    LlmUpstreamError,
    OpenAICompatibleStreamClient,
    build_novel_messages,
)
from inkflow_agent.streaming.protocol import (
    ControlSignal,
    RunResumeRequest,
    RunStartRequest,
    RunStateCallback,
)

logger = logging.getLogger(__name__)


def stream_key(run_id: str) -> str:
    return f"inkflow:run:{run_id}:events"


def control_key(run_id: str) -> str:
    return f"inkflow:run:{run_id}:ctrl"


class StateReporter(Protocol):
    async def report(self, run_id: str, state: RunStateCallback) -> None: ...


class HttpBizStateReporter:
    """Reports run checkpoints and terminal statuses back to biz-java."""

    def __init__(self, settings: Settings) -> None:
        self._base_url = settings.biz_java_base_url.rstrip("/")
        self._token = settings.internal_service_token

    async def report(self, run_id: str, state: RunStateCallback) -> None:
        url = f"{self._base_url}/internal/v1/runs/{run_id}/state"
        headers = {"X-Internal-Token": self._token}
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(url, headers=headers, json=state.model_dump())
                if resp.status_code >= 400:
                    logger.warning(
                        "Failed to report run state for %s: HTTP %s %s",
                        run_id,
                        resp.status_code,
                        resp.text[:200],
                    )
        except httpx.HTTPError as exc:
            logger.warning("Could not reach biz-java state callback for %s: %s", run_id, exc)


@dataclass
class _ActiveRun:
    run_id: str
    control_event: asyncio.Event = field(default_factory=asyncio.Event)
    signal: ControlSignal | None = None
    task: asyncio.Task[None] | None = None


class RunManager:
    """Manages active streaming generations, writing structured events to Redis Streams."""

    def __init__(
        self,
        *,
        settings: Settings,
        redis: Redis,
        llm_client: OpenAICompatibleStreamClient | None = None,
        state_reporter: StateReporter | None = None,
    ) -> None:
        self._settings = settings
        self._redis = redis
        self._llm = llm_client or OpenAICompatibleStreamClient(settings)
        self._reporter = state_reporter or HttpBizStateReporter(settings)
        self._active: dict[str, _ActiveRun] = {}

    async def start_run(self, req: RunStartRequest) -> None:
        await self._redis.delete(control_key(req.run_id))
        active = _ActiveRun(run_id=req.run_id)
        self._active[req.run_id] = active
        active.task = asyncio.create_task(
            self._execute_segment(
                active=active,
                run_id=req.run_id,
                trace_id=req.trace_id,
                action=req.action,
                model_alias=req.model_alias,
                book_title=req.book_title,
                genre=req.genre,
                synopsis=req.synopsis,
                chapter_title=req.chapter_title,
                chapter_context=req.chapter_context,
                instruction=req.instruction,
                accumulated_text="",
                start_seq=0,
                prior_input_tokens=0,
                prior_cached_tokens=0,
                prior_output_tokens=0,
                resumed=False,
            ),
            name=f"inkflow-run-{req.run_id}",
        )

    async def resume_run(self, req: RunResumeRequest) -> None:
        await self._redis.delete(control_key(req.run_id))
        active = _ActiveRun(run_id=req.run_id)
        self._active[req.run_id] = active
        active.task = asyncio.create_task(
            self._execute_segment(
                active=active,
                run_id=req.run_id,
                trace_id=req.trace_id,
                action=req.action,
                model_alias=req.model_alias,
                book_title=req.book_title,
                genre=req.genre,
                synopsis=req.synopsis,
                chapter_title=req.chapter_title,
                chapter_context=req.chapter_context,
                instruction=req.instruction,
                accumulated_text=req.accumulated_text,
                start_seq=req.last_seq,
                prior_input_tokens=req.prior_input_tokens,
                prior_cached_tokens=req.prior_cached_tokens,
                prior_output_tokens=req.prior_output_tokens,
                resumed=True,
            ),
            name=f"inkflow-run-resume-{req.run_id}",
        )

    async def send_control(self, run_id: str, signal: ControlSignal) -> bool:
        await self._redis.set(control_key(run_id), signal, ex=self._settings.stream_ttl_seconds)
        active = self._active.get(run_id)
        if active is not None:
            active.signal = signal
            active.control_event.set()
            return True
        return False

    async def shutdown(self) -> None:
        tasks = [a.task for a in self._active.values() if a.task is not None and not a.task.done()]
        for active in self._active.values():
            active.signal = "CANCEL"
            active.control_event.set()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _emit(
        self,
        *,
        run_id: str,
        trace_id: str | None,
        seq: int,
        event_type: str,
        payload: dict[str, Any],
        agent: str = "writer",
    ) -> str:
        key = stream_key(run_id)
        fields = {
            "type": event_type,
            "seq": str(seq),
            "run_id": run_id,
            "trace_id": trace_id or "",
            "agent": agent,
            "timestamp": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "payload": json.dumps(payload, ensure_ascii=False),
        }
        event_id: str = await self._redis.xadd(key, fields)
        await self._redis.expire(key, self._settings.stream_ttl_seconds)
        return event_id

    async def _check_control(self, active: _ActiveRun) -> ControlSignal | None:
        if active.signal is not None:
            return active.signal
        raw = await self._redis.get(control_key(active.run_id))
        if raw in ("PAUSE", "CANCEL"):
            active.signal = raw
            active.control_event.set()
            return raw
        return None

    async def _execute_segment(
        self,
        *,
        active: _ActiveRun,
        run_id: str,
        trace_id: str | None,
        action: Any,
        model_alias: str,
        book_title: str,
        genre: str | None,
        synopsis: str | None,
        chapter_title: str,
        chapter_context: str,
        instruction: str,
        accumulated_text: str,
        start_seq: int,
        prior_input_tokens: int,
        prior_cached_tokens: int,
        prior_output_tokens: int,
        resumed: bool,
    ) -> None:
        seq = start_seq
        last_event_id: str | None = None
        parts = [accumulated_text] if accumulated_text else []
        seg_input = 0
        seg_cached = 0
        seg_output = 0

        try:
            seq += 1
            last_event_id = await self._emit(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                event_type="run_started",
                payload={
                    "action": action,
                    "model_alias": model_alias,
                    "resumed": resumed,
                },
            )
            await self._reporter.report(
                run_id,
                RunStateCallback(
                    status="RUNNING",
                    accumulated_text="".join(parts),
                    last_seq=seq,
                    last_event_id=last_event_id,
                    input_tokens=prior_input_tokens,
                    cached_tokens=prior_cached_tokens,
                    output_tokens=prior_output_tokens,
                ),
            )

            messages = build_novel_messages(
                action=action,
                book_title=book_title,
                genre=genre,
                synopsis=synopsis,
                chapter_title=chapter_title,
                chapter_context=chapter_context,
                instruction=instruction,
                accumulated_text=accumulated_text,
            )

            stream_iter = self._llm.stream_chat(model_alias=model_alias, messages=messages)
            try:
                while True:
                    ctrl = await self._check_control(active)
                    if ctrl is not None:
                        break

                    next_chunk_task = asyncio.create_task(anext(stream_iter))
                    ctrl_wait_task = asyncio.create_task(active.control_event.wait())
                    done, pending = await asyncio.wait(
                        {next_chunk_task, ctrl_wait_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    for task in pending:
                        task.cancel()
                        with contextlib.suppress(asyncio.CancelledError):
                            await task

                    if ctrl_wait_task in done and active.control_event.is_set():
                        break

                    try:
                        chunk: LlmStreamChunk = next_chunk_task.result()
                    except StopAsyncIteration:
                        break

                    if chunk.reasoning_delta:
                        seq += 1
                        last_event_id = await self._emit(
                            run_id=run_id,
                            trace_id=trace_id,
                            seq=seq,
                            event_type="thought_delta",
                            payload={"content": chunk.reasoning_delta},
                        )

                    if chunk.content_delta:
                        parts.append(chunk.content_delta)
                        seq += 1
                        last_event_id = await self._emit(
                            run_id=run_id,
                            trace_id=trace_id,
                            seq=seq,
                            event_type="text_delta",
                            payload={"content": chunk.content_delta},
                        )

                    if chunk.input_tokens is not None:
                        seg_input = chunk.input_tokens
                    if chunk.cached_tokens is not None:
                        seg_cached = chunk.cached_tokens
                    if chunk.output_tokens is not None:
                        seg_output = chunk.output_tokens
            finally:
                with contextlib.suppress(Exception):
                    await stream_iter.aclose()

            total_in = prior_input_tokens + seg_input
            total_cached = prior_cached_tokens + seg_cached
            total_out = prior_output_tokens + seg_output
            full_text = "".join(parts)

            ctrl = await self._check_control(active)
            if ctrl == "PAUSE":
                checkpoint_id = f"ckpt-{seq}"
                seq += 1
                # Report PAUSED checkpoint to PG BEFORE emitting interrupt so immediate Resume sees PAUSED.
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="PAUSED",
                        accumulated_text=full_text,
                        last_seq=seq,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                    ),
                )
                last_event_id = await self._emit(
                    run_id=run_id,
                    trace_id=trace_id,
                    seq=seq,
                    event_type="interrupt",
                    payload={
                        "checkpoint_id": checkpoint_id,
                        "reason": "USER_PAUSED",
                        "proposed_data": {
                            "accumulated_chars": len(full_text),
                            "instruction": instruction,
                        },
                    },
                )
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="PAUSED",
                        accumulated_text=full_text,
                        last_seq=seq,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                    ),
                )
                return

            if ctrl == "CANCEL":
                seq += 1
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="CANCELLED",
                        accumulated_text=full_text,
                        last_seq=seq,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                    ),
                )
                last_event_id = await self._emit(
                    run_id=run_id,
                    trace_id=trace_id,
                    seq=seq,
                    event_type="done",
                    payload={"status": "cancelled", "accumulated_chars": len(full_text)},
                )
                return

            # Normal completion: emit usage then report PG COMPLETED then emit done
            seq += 1
            last_event_id = await self._emit(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                event_type="usage",
                payload={
                    "model_alias": model_alias,
                    "input_tokens": total_in,
                    "cached_tokens": total_cached,
                    "output_tokens": total_out,
                },
            )
            seq += 1
            await self._reporter.report(
                run_id,
                RunStateCallback(
                    status="COMPLETED",
                    accumulated_text=full_text,
                    last_seq=seq,
                    last_event_id=last_event_id,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                ),
            )
            last_event_id = await self._emit(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                event_type="done",
                payload={"status": "completed", "accumulated_chars": len(full_text)},
            )

        except LlmConfigurationError as exc:
            await self._handle_failure(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                accumulated_text="".join(parts),
                code="LLM_API_KEY_NOT_CONFIGURED",
                message=str(exc),
                retryable=False,
                in_tok=prior_input_tokens + seg_input,
                cached_tok=prior_cached_tokens + seg_cached,
                out_tok=prior_output_tokens + seg_output,
            )
        except LlmUpstreamError as exc:
            await self._handle_failure(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                accumulated_text="".join(parts),
                code=exc.code,
                message=str(exc),
                retryable=exc.retryable,
                in_tok=prior_input_tokens + seg_input,
                cached_tok=prior_cached_tokens + seg_cached,
                out_tok=prior_output_tokens + seg_output,
            )
        except Exception as exc:
            logger.exception("Unexpected failure in streaming run %s", run_id)
            await self._handle_failure(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                accumulated_text="".join(parts),
                code="AGENT_INTERNAL_ERROR",
                message=f"Agent 执行异常: {exc}",
                retryable=False,
                in_tok=prior_input_tokens + seg_input,
                cached_tok=prior_cached_tokens + seg_cached,
                out_tok=prior_output_tokens + seg_output,
            )
        finally:
            if self._active.get(run_id) is active:
                self._active.pop(run_id, None)

    async def _handle_failure(
        self,
        *,
        run_id: str,
        trace_id: str | None,
        seq: int,
        accumulated_text: str,
        code: str,
        message: str,
        retryable: bool,
        in_tok: int,
        cached_tok: int,
        out_tok: int,
    ) -> None:
        next_seq = seq + 1
        await self._reporter.report(
            run_id,
            RunStateCallback(
                status="FAILED",
                accumulated_text=accumulated_text,
                last_seq=next_seq,
                input_tokens=in_tok,
                cached_tokens=cached_tok,
                output_tokens=out_tok,
                error_message=message,
            ),
        )
        await self._emit(
            run_id=run_id,
            trace_id=trace_id,
            seq=next_seq,
            event_type="error",
            payload={"code": code, "message": message, "retryable": retryable},
        )
