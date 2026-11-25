import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol

import httpx
from redis.asyncio import Redis

from foliarc_agent.agent.context import ContextManager
from foliarc_agent.agent.guardrails import GuardrailEngine
from foliarc_agent.agent.loop import AgentLoopConfig, ReActAgentLoop
from foliarc_agent.agent.mcp_client import McpLoreClient
from foliarc_agent.agent.multi_agent import (
    CheckpointStore,
    EditorialBlackboard,
    EditorialTeamOrchestrator,
    HttpBizCheckpointStore,
)
from foliarc_agent.agent.prompts import PromptRegistry
from foliarc_agent.agent.router import FallbackStreamClient, IntentAndModelRouter
from foliarc_agent.agent.sandbox import SandboxProvider, StubSandboxProvider
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.agent.tools import ToolRegistry, register_builtin_novel_tools
from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.streaming.llm_client import (
    LlmConfigurationError,
    LlmStreamChunk,
    LlmUpstreamError,
    OpenAICompatibleStreamClient,
    build_novel_messages,
)
from foliarc_agent.streaming.protocol import (
    ControlSignal,
    RunMode,
    RunResumeRequest,
    RunStartRequest,
    RunStateCallback,
)

logger = logging.getLogger(__name__)


def stream_key(run_id: str) -> str:
    return f"foliarc:run:{run_id}:events"


def control_key(run_id: str) -> str:
    return f"foliarc:run:{run_id}:ctrl"


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
    """Manages active streaming generations (both DIRECT and AGENT ReAct modes) over Redis Streams."""

    def __init__(
        self,
        *,
        settings: Settings,
        redis: Redis,
        llm_client: OpenAICompatibleStreamClient | None = None,
        state_reporter: StateReporter | None = None,
        skill_registry: SkillRegistry | None = None,
        rag_retriever: HybridRagRetriever | None = None,
        embedder: EmbeddingProvider | None = None,
        checkpoint_store: CheckpointStore | None = None,
        sandbox_provider: SandboxProvider | None = None,
        guardrails: GuardrailEngine | None = None,
        enable_mcp: bool = True,
    ) -> None:
        self._settings = settings
        self._redis = redis
        self._llm = llm_client or OpenAICompatibleStreamClient(settings)
        self._fallback_llm = FallbackStreamClient(self._llm)
        self._reporter = state_reporter or HttpBizStateReporter(settings)
        self._skills = skill_registry or SkillRegistry()
        self._prompts = PromptRegistry(settings)
        self._rag_retriever = rag_retriever
        self._router = IntentAndModelRouter(settings=settings, embedder=embedder)
        self._checkpoint_store = checkpoint_store or HttpBizCheckpointStore(settings)
        self._sandbox = sandbox_provider or StubSandboxProvider()
        self._guardrails = guardrails or GuardrailEngine(settings)
        self._enable_mcp = enable_mcp
        self._active: dict[str, _ActiveRun] = {}

    @property
    def skill_registry(self) -> SkillRegistry:
        return self._skills

    async def start_run(self, req: RunStartRequest) -> None:
        await self._redis.delete(control_key(req.run_id))
        active = _ActiveRun(run_id=req.run_id)
        self._active[req.run_id] = active
        active.task = asyncio.create_task(
            self._execute_segment(
                active=active,
                run_id=req.run_id,
                owner_id=req.owner_id,
                book_id=req.book_id,
                chapter_no=req.chapter_no,
                section_id=req.section_id,
                narrative_order=req.narrative_order,
                precision_mode=req.precision_mode,
                mode=req.mode,
                trace_id=req.trace_id,
                action=req.action,
                model_alias=req.model_alias,
                book_title=req.book_title,
                genre=req.genre,
                synopsis=req.synopsis,
                story_charter=req.story_charter,
                chapter_title=req.chapter_title,
                chapter_context=req.chapter_context,
                previous_chapters_context=req.previous_chapters_context,
                rolling_summary_context=req.rolling_summary_context,
                active_state_brief=req.active_state_brief,
                pov_character=req.pov_character,
                foreshadowing_writer_pack=req.foreshadowing_writer_pack,
                foreshadowing_critic_pack=req.foreshadowing_critic_pack,
                critic_guard_brief=req.critic_guard_brief,
                instruction=req.instruction,
                accumulated_text="",
                start_seq=0,
                prior_input_tokens=0,
                prior_cached_tokens=0,
                prior_output_tokens=0,
                resumed=False,
                require_outline_approval=req.require_outline_approval,
                approval_payload=None,
            ),
            name=f"foliarc-run-{req.run_id}",
        )

    async def resume_run(self, req: RunResumeRequest) -> None:
        await self._redis.delete(control_key(req.run_id))
        active = _ActiveRun(run_id=req.run_id)
        self._active[req.run_id] = active
        active.task = asyncio.create_task(
            self._execute_segment(
                active=active,
                run_id=req.run_id,
                owner_id=req.owner_id,
                book_id=req.book_id,
                chapter_no=req.chapter_no,
                section_id=req.section_id,
                narrative_order=req.narrative_order,
                precision_mode=req.precision_mode,
                mode=req.mode,
                trace_id=req.trace_id,
                action=req.action,
                model_alias=req.model_alias,
                book_title=req.book_title,
                genre=req.genre,
                synopsis=req.synopsis,
                story_charter=req.story_charter,
                chapter_title=req.chapter_title,
                chapter_context=req.chapter_context,
                previous_chapters_context=req.previous_chapters_context,
                rolling_summary_context=req.rolling_summary_context,
                active_state_brief=req.active_state_brief,
                pov_character=req.pov_character,
                foreshadowing_writer_pack=req.foreshadowing_writer_pack,
                foreshadowing_critic_pack=req.foreshadowing_critic_pack,
                critic_guard_brief=req.critic_guard_brief,
                instruction=req.instruction,
                accumulated_text=req.accumulated_text,
                start_seq=req.last_seq,
                prior_input_tokens=req.prior_input_tokens,
                prior_cached_tokens=req.prior_cached_tokens,
                prior_output_tokens=req.prior_output_tokens,
                resumed=True,
                require_outline_approval=True,
                approval_payload=req.approval_payload,
            ),
            name=f"foliarc-run-resume-{req.run_id}",
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
        owner_id: str,
        book_id: str,
        chapter_no: int | None,
        section_id: str | None = None,
        narrative_order: int | None = None,
        precision_mode: str = "AUTO",
        mode: RunMode,
        trace_id: str | None,
        action: Any,
        model_alias: str,
        book_title: str,
        genre: str | None,
        synopsis: str | None,
        story_charter: str = "",
        chapter_title: str,
        chapter_context: str,
        previous_chapters_context: str = "",
        rolling_summary_context: str = "",
        active_state_brief: str = "",
        pov_character: str = "",
        foreshadowing_writer_pack: str = "",
        foreshadowing_critic_pack: str = "",
        critic_guard_brief: str = "",
        instruction: str,
        accumulated_text: str,
        start_seq: int,
        prior_input_tokens: int,
        prior_cached_tokens: int,
        prior_output_tokens: int,
        resumed: bool,
        require_outline_approval: bool = True,
        approval_payload: dict[str, Any] | None = None,
    ) -> None:
        seq = start_seq
        last_event_id: str | None = None
        full_text = accumulated_text
        seg_input = 0
        seg_cached = 0
        seg_output = 0
        current_node: str | None = None
        route_intent: str | None = None
        revision_count: int | None = None
        hitl_interrupt_type: str | None = None
        hitl_interrupt_payload: dict[str, Any] | None = None

        async def emit_next(event_type: str, payload: dict[str, Any], agent: str = "writer") -> str:
            nonlocal seq, last_event_id
            seq += 1
            last_event_id = await self._emit(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                event_type=event_type,
                payload=payload,
                agent=agent,
            )
            return last_event_id

        try:
            # Phase 5 Input Guardrail: block Prompt Injection / Jailbreak / Compliance before burning tokens
            in_verdict = self._guardrails.check_input(instruction, chapter_context)
            if not in_verdict.allowed:
                await self._guardrails.report_audit(
                    verdict=in_verdict,
                    user_id=owner_id,
                    book_id=book_id,
                    run_id=run_id,
                    detail=f"[{in_verdict.rule_code}] {instruction[:200]}",
                )
                await self._handle_failure(
                    run_id=run_id,
                    trace_id=trace_id,
                    seq=seq,
                    accumulated_text=full_text,
                    code=f"GUARDRAIL_{in_verdict.rule_code}",
                    message=in_verdict.message,
                    retryable=False,
                    in_tok=prior_input_tokens,
                    cached_tok=prior_cached_tokens,
                    out_tok=prior_output_tokens,
                )
                return

            async with contextlib.AsyncExitStack() as stack:
                tool_registry = ToolRegistry()
                mcp_tool_names: list[str] = []

                if mode == "AGENT":
                    register_builtin_novel_tools(
                        tool_registry,
                        skill_registry=self._skills,
                        biz_java_url=self._settings.biz_java_base_url,
                        internal_token=self._settings.internal_service_token,
                        owner_id=owner_id,
                        book_id=book_id,
                        run_id=run_id,
                        chapter_no=chapter_no,
                        chapter_context=chapter_context,
                        rag_retriever=self._rag_retriever,
                        sandbox_provider=self._sandbox,
                    )
                    if self._enable_mcp and owner_id and book_id:
                        try:
                            mcp_client = await stack.enter_async_context(
                                McpLoreClient(
                                    biz_java_url=self._settings.biz_java_base_url,
                                    internal_token=self._settings.internal_service_token,
                                    owner_id=owner_id,
                                    book_id=book_id,
                                )
                            )
                            mcp_tool_names = await tool_registry.register_mcp_tools(mcp_client)
                        except (OSError, RuntimeError, ValueError, TimeoutError) as exc:
                            logger.warning("MCP stdio client unavailable for run %s: %s", run_id, exc)

                await emit_next(
                    "run_started",
                    {
                        "mode": mode,
                        "action": action,
                        "model_alias": model_alias,
                        "resumed": resumed,
                        "tools": tool_registry.tool_names() if mode == "AGENT" else [],
                        "mcp_tools": mcp_tool_names,
                    },
                )
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="RUNNING",
                        accumulated_text=full_text,
                        last_seq=seq,
                        last_event_id=last_event_id,
                        input_tokens=prior_input_tokens,
                        cached_tokens=prior_cached_tokens,
                        output_tokens=prior_output_tokens,
                    ),
                )

                if mode == "MULTI_AGENT":
                    orchestrator = EditorialTeamOrchestrator(
                        settings=self._settings,
                        llm_client=self._fallback_llm,
                        router=self._router,
                        checkpoint_store=self._checkpoint_store,
                        skill_registry=self._skills,
                        rag_retriever=self._rag_retriever,
                        enable_mcp=self._enable_mcp,
                    )
                    board = EditorialBlackboard(
                        run_id=run_id,
                        owner_id=owner_id,
                        book_id=book_id,
                        chapter_no=chapter_no,
                        section_id=section_id,
                        narrative_order=narrative_order,
                        precision_mode=precision_mode,
                        action=str(action),
                        book_title=book_title,
                        genre=genre,
                        synopsis=synopsis,
                        story_charter=story_charter,
                        chapter_title=chapter_title,
                        chapter_context=chapter_context,
                        previous_chapters_context=previous_chapters_context,
                        rolling_summary_context=rolling_summary_context,
                        active_state_brief=active_state_brief,
                        pov_character=pov_character,
                        foreshadowing_writer_pack=foreshadowing_writer_pack,
                        foreshadowing_critic_pack=foreshadowing_critic_pack,
                        critic_guard_brief=critic_guard_brief,
                        instruction=instruction,
                        requested_model_alias=model_alias,
                        require_outline_approval=require_outline_approval,
                    )
                    ma_res = await orchestrator.run(
                        initial_state=board,
                        emit=emit_next,
                        control_event=active.control_event,
                        check_control=lambda: self._check_control(active),
                        resumed=resumed,
                        approval_payload=approval_payload,
                    )
                    full_text = ma_res.accumulated_text
                    seg_input = ma_res.input_tokens
                    seg_cached = ma_res.cached_tokens
                    seg_output = ma_res.output_tokens
                    current_node = ma_res.current_node
                    route_intent = ma_res.route_intent
                    revision_count = ma_res.revision_count
                    hitl_interrupt_type = ma_res.interrupt_type
                    hitl_interrupt_payload = ma_res.interrupt_payload
                    final_outcome = ma_res.status
                elif mode == "AGENT":
                    rendered = await self._prompts.render_writer_system(
                        action=action,
                        book_title=book_title,
                        genre=genre,
                        synopsis=story_charter.strip() or synopsis,
                        skill_catalog=self._skills.render_catalog_prompt(),
                    )
                    user_parts = []
                    if chapter_no is not None or chapter_title:
                        ch_label = f"第 {chapter_no} 章 " if chapter_no else ""
                        user_parts.append(f"当前章节：{ch_label}{chapter_title}".strip())
                    if rolling_summary_context.strip():
                        user_parts.append(
                            f"【前文卷级里程碑与近期章摘（L2）】\n{rolling_summary_context.strip()[:4200]}"
                        )
                    if active_state_brief.strip():
                        user_parts.append(
                            f"【当前活跃人物状态与未回收伏笔（L3）】\n{active_state_brief.strip()[:1800]}"
                        )
                    if pov_character.strip():
                        user_parts.append(
                            f"【本章叙事视角（POV）】{pov_character.strip()}（严禁视角角色凭空知晓幕后客观事实！）"
                        )
                    if foreshadowing_writer_pack.strip():
                        user_parts.append(
                            f"【本章强制伏笔导演包（严格遵守防剧透红线）】\n{foreshadowing_writer_pack.strip()[:2200]}"
                        )
                    if previous_chapters_context.strip():
                        user_parts.append(
                            f"【前文章节承接（L1 热窗口，必须紧密衔接上一章结尾，禁止割裂剧情）】\n{previous_chapters_context.strip()[-2800:]}"
                        )
                    elif chapter_no and chapter_no > 1 and not chapter_context.strip():
                        user_parts.append(
                            f"【衔接提示】当前为第 {chapter_no} 章且正文尚空，动笔前请优先调用 read_chapter_excerpt(chapter_no={chapter_no - 1}) 阅读上一章结尾。"
                        )
                    if chapter_context.strip():
                        user_parts.append(f"【当前章节已有正文】\n{chapter_context[-4000:]}")
                    if instruction.strip():
                        user_parts.append(f"【作者指令】\n{instruction.strip()}")
                    else:
                        user_parts.append("【作者指令】\n请紧接上一章结尾与小说设定自然推进剧情，输出高质量正文。")

                    context_manager = ContextManager()
                    loop = ReActAgentLoop(
                        llm_client=self._llm,
                        tool_registry=tool_registry,
                        context_manager=context_manager,
                        config=AgentLoopConfig(),
                    )
                    result = await loop.run(
                        model_alias=model_alias,
                        static_system_prompt=rendered.system_prompt,
                        initial_user_prompt="\n\n".join(user_parts),
                        emit=emit_next,
                        control_event=active.control_event,
                        check_control=lambda: self._check_control(active),
                        accumulated_text=accumulated_text,
                        revised_instruction=instruction if resumed else "",
                    )
                    full_text = result.accumulated_text
                    seg_input = result.input_tokens
                    seg_cached = result.cached_tokens
                    seg_output = result.output_tokens
                    final_outcome = result.status
                else:
                    parts = [accumulated_text] if accumulated_text else []
                    messages = build_novel_messages(
                        action=action,
                        book_title=book_title,
                        genre=genre,
                        synopsis=synopsis,
                        story_charter=story_charter,
                        chapter_title=chapter_title,
                        chapter_context=chapter_context,
                        previous_chapters_context=previous_chapters_context,
                        rolling_summary_context=rolling_summary_context,
                        active_state_brief=active_state_brief,
                        pov_character=pov_character,
                        foreshadowing_writer_pack=foreshadowing_writer_pack,
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
                                await emit_next("thought_delta", {"content": chunk.reasoning_delta})
                            if chunk.content_delta:
                                parts.append(chunk.content_delta)
                                await emit_next("text_delta", {"content": chunk.content_delta})
                            if chunk.input_tokens is not None:
                                seg_input = chunk.input_tokens
                            if chunk.cached_tokens is not None:
                                seg_cached = chunk.cached_tokens
                            if chunk.output_tokens is not None:
                                seg_output = chunk.output_tokens
                    finally:
                        with contextlib.suppress(Exception):
                            await stream_iter.aclose()

                    full_text = "".join(parts)
                    ctrl = await self._check_control(active)
                    final_outcome = "PAUSED" if ctrl == "PAUSE" else ("CANCELLED" if ctrl == "CANCEL" else "COMPLETED")

            total_in = prior_input_tokens + seg_input
            total_cached = prior_cached_tokens + seg_cached
            total_out = prior_output_tokens + seg_output

            if final_outcome == "INTERRUPTED" and hitl_interrupt_payload is not None:
                payload_json = json.dumps(hitl_interrupt_payload, ensure_ascii=False)
                next_seq = seq + 1
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="PAUSED",
                        accumulated_text=full_text,
                        last_seq=next_seq,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                        current_node=current_node,
                        interrupt_type=hitl_interrupt_type or "OUTLINE_APPROVAL",
                        interrupt_payload_json=payload_json,
                        route_intent=route_intent,
                        revision_count=revision_count,
                    ),
                )
                await emit_next(
                    "interrupt",
                    {
                        "checkpoint_id": f"ckpt-step-{hitl_interrupt_payload.get('checkpoint_step', seq)}",
                        "reason": hitl_interrupt_type or "OUTLINE_APPROVAL",
                        "interrupt_type": hitl_interrupt_type or "OUTLINE_APPROVAL",
                        "proposed_data": hitl_interrupt_payload,
                    },
                    agent="director",
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
                        current_node=current_node,
                        interrupt_type=hitl_interrupt_type or "OUTLINE_APPROVAL",
                        interrupt_payload_json=payload_json,
                        route_intent=route_intent,
                        revision_count=revision_count,
                    ),
                )
                return

            if final_outcome == "PAUSED":
                checkpoint_id = f"ckpt-{seq}"
                next_seq = seq + 1
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="PAUSED",
                        accumulated_text=full_text,
                        last_seq=next_seq,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                    ),
                )
                await emit_next(
                    "interrupt",
                    {
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

            if final_outcome == "CANCELLED":
                await self._reporter.report(
                    run_id,
                    RunStateCallback(
                        status="CANCELLED",
                        accumulated_text=full_text,
                        last_seq=seq + 1,
                        last_event_id=last_event_id,
                        input_tokens=total_in,
                        cached_tokens=total_cached,
                        output_tokens=total_out,
                    ),
                )
                await emit_next(
                    "done",
                    {"status": "cancelled", "accumulated_chars": len(full_text)},
                )
                return

            # Phase 5 Output Guardrail: detect secret / system-prompt leak or compliance breach
            out_verdict = self._guardrails.check_output(full_text)
            if not out_verdict.allowed:
                await self._guardrails.report_audit(
                    verdict=out_verdict,
                    user_id=owner_id,
                    book_id=book_id,
                    run_id=run_id,
                    detail=out_verdict.message,
                )
                await self._handle_failure(
                    run_id=run_id,
                    trace_id=trace_id,
                    seq=seq,
                    accumulated_text="",
                    code=f"GUARDRAIL_{out_verdict.rule_code}",
                    message=out_verdict.message,
                    retryable=False,
                    in_tok=total_in,
                    cached_tok=total_cached,
                    out_tok=total_out,
                )
                return

            await emit_next(
                "usage",
                {
                    "model_alias": model_alias,
                    "input_tokens": total_in,
                    "cached_tokens": total_cached,
                    "output_tokens": total_out,
                },
            )
            await self._reporter.report(
                run_id,
                RunStateCallback(
                    status="COMPLETED",
                    accumulated_text=full_text,
                    last_seq=seq + 1,
                    last_event_id=last_event_id,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    current_node=current_node,
                    route_intent=route_intent,
                    revision_count=revision_count,
                ),
            )
            await emit_next(
                "done",
                {"status": "completed", "accumulated_chars": len(full_text)},
            )

        except LlmConfigurationError as exc:
            await self._handle_failure(
                run_id=run_id,
                trace_id=trace_id,
                seq=seq,
                accumulated_text=full_text,
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
                accumulated_text=full_text,
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
                accumulated_text=full_text,
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
