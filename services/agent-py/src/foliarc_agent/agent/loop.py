from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Literal

from foliarc_agent.agent.context import ContextManager
from foliarc_agent.agent.tools import ToolRegistry
from foliarc_agent.streaming.llm_client import LlmStreamChunk, OpenAICompatibleStreamClient
from foliarc_agent.streaming.protocol import ControlSignal

EmitCallback = Callable[[str, dict[str, Any]], Awaitable[str]]
ControlCheck = Callable[[], Awaitable[ControlSignal | None]]


@dataclass(slots=True)
class AgentLoopConfig:
    max_steps: int = 8
    max_token_budget: int = 32000
    max_identical_calls: int = 2


@dataclass(slots=True)
class AgentLoopResult:
    status: Literal["COMPLETED", "PAUSED", "CANCELLED"]
    accumulated_text: str
    steps_executed: int
    tool_calls_count: int
    input_tokens: int
    cached_tokens: int
    output_tokens: int
    loop_guard_triggered: bool = False
    budget_guard_triggered: bool = False


@dataclass(slots=True)
class _AssembledToolCall:
    index: int
    id: str = ""
    name: str = ""
    arguments_parts: list[str] = field(default_factory=list)

    @property
    def arguments_raw(self) -> str:
        return "".join(self.arguments_parts)


def _canonical_signature(tool_name: str, raw_args: str) -> str:
    try:
        normalized = json.dumps(json.loads(raw_args), sort_keys=True, ensure_ascii=False)
    except (json.JSONDecodeError, TypeError, ValueError):
        normalized = raw_args.strip()
    digest = hashlib.sha256(f"{tool_name}:{normalized}".encode()).hexdigest()[:16]
    return f"{tool_name}:{digest}"


class ReActAgentLoop:
    """
    Hand-crafted framework-free ReAct Agent Loop featuring:
    - Streaming reasoning (`thought_delta`), tool calls (`tool_call`/`tool_result`), and final prose (`text_delta`)
    - Parallel tool execution + Pydantic error self-healing
    - Guardrails: `max_steps`, cumulative Token budget circuit breaker, and identical-call infinite loop detector
    - Cooperative Pause/Cancel interruption via `control_event`
    - Context Engineering: immutable KV Cache static prefix + Tool Output Offloading + Rolling Summary compression
    """

    def __init__(
        self,
        *,
        llm_client: OpenAICompatibleStreamClient,
        tool_registry: ToolRegistry,
        context_manager: ContextManager,
        config: AgentLoopConfig | None = None,
    ) -> None:
        self._llm = llm_client
        self._tools = tool_registry
        self._context = context_manager
        self._config = config or AgentLoopConfig()

    async def run(
        self,
        *,
        model_alias: str,
        static_system_prompt: str,
        initial_user_prompt: str,
        emit: EmitCallback,
        control_event: asyncio.Event,
        check_control: ControlCheck,
        accumulated_text: str = "",
        revised_instruction: str = "",
    ) -> AgentLoopResult:
        parts = [accumulated_text] if accumulated_text else []
        loop_messages: list[dict[str, Any]] = []

        total_in = 0
        total_cached = 0
        total_out = 0
        tool_calls_count = 0
        steps_executed = 0

        sig_counts: dict[str, int] = {}
        loop_guard_triggered = False
        budget_guard_triggered = False
        force_final = False

        openai_tools = self._tools.to_openai_tools()

        for step in range(1, self._config.max_steps + 1):
            ctrl = await check_control()
            if ctrl == "PAUSE":
                return AgentLoopResult(
                    status="PAUSED",
                    accumulated_text="".join(parts),
                    steps_executed=steps_executed,
                    tool_calls_count=tool_calls_count,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    loop_guard_triggered=loop_guard_triggered,
                    budget_guard_triggered=budget_guard_triggered,
                )
            if ctrl == "CANCEL":
                return AgentLoopResult(
                    status="CANCELLED",
                    accumulated_text="".join(parts),
                    steps_executed=steps_executed,
                    tool_calls_count=tool_calls_count,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    loop_guard_triggered=loop_guard_triggered,
                    budget_guard_triggered=budget_guard_triggered,
                )

            steps_executed = step
            is_last_step = step >= self._config.max_steps
            active_tools = None if (force_final or is_last_step or not openai_tools) else openai_tools

            messages = self._context.build_messages(
                static_system_prompt=static_system_prompt,
                initial_user_prompt=initial_user_prompt,
                loop_messages=loop_messages,
                accumulated_text=accumulated_text if step == 1 else "",
                revised_instruction=revised_instruction if step == 1 else "",
            )

            step_text_parts: list[str] = []
            assembled_calls: dict[int, _AssembledToolCall] = {}
            step_in = 0
            step_cached = 0
            step_out = 0

            stream_iter = self._llm.stream_chat(
                model_alias=model_alias,
                messages=messages,
                tools=active_tools,
            )
            try:
                while True:
                    ctrl = await check_control()
                    if ctrl is not None:
                        break

                    next_task = asyncio.create_task(anext(stream_iter))
                    ctrl_task = asyncio.create_task(control_event.wait())
                    done, pending = await asyncio.wait(
                        {next_task, ctrl_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    for task in pending:
                        task.cancel()
                        with contextlib.suppress(asyncio.CancelledError):
                            await task

                    if ctrl_task in done and control_event.is_set():
                        break

                    try:
                        chunk: LlmStreamChunk = next_task.result()
                    except StopAsyncIteration:
                        break

                    if chunk.reasoning_delta:
                        await emit("thought_delta", {"content": chunk.reasoning_delta})

                    if chunk.content_delta:
                        step_text_parts.append(chunk.content_delta)
                        parts.append(chunk.content_delta)
                        await emit("text_delta", {"content": chunk.content_delta})

                    for tc_delta in chunk.tool_call_deltas:
                        slot = assembled_calls.get(tc_delta.index)
                        if slot is None:
                            slot = _AssembledToolCall(index=tc_delta.index)
                            assembled_calls[tc_delta.index] = slot
                        if tc_delta.id:
                            slot.id = tc_delta.id
                        if tc_delta.name:
                            slot.name += tc_delta.name
                        if tc_delta.arguments_delta:
                            slot.arguments_parts.append(tc_delta.arguments_delta)

                    if chunk.input_tokens is not None:
                        step_in = chunk.input_tokens
                    if chunk.cached_tokens is not None:
                        step_cached = chunk.cached_tokens
                    if chunk.output_tokens is not None:
                        step_out = chunk.output_tokens
            finally:
                with contextlib.suppress(Exception):
                    await stream_iter.aclose()

            total_in += step_in
            total_cached += step_cached
            total_out += step_out

            ctrl = await check_control()
            if ctrl == "PAUSE":
                return AgentLoopResult(
                    status="PAUSED",
                    accumulated_text="".join(parts),
                    steps_executed=steps_executed,
                    tool_calls_count=tool_calls_count,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    loop_guard_triggered=loop_guard_triggered,
                    budget_guard_triggered=budget_guard_triggered,
                )
            if ctrl == "CANCEL":
                return AgentLoopResult(
                    status="CANCELLED",
                    accumulated_text="".join(parts),
                    steps_executed=steps_executed,
                    tool_calls_count=tool_calls_count,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    loop_guard_triggered=loop_guard_triggered,
                    budget_guard_triggered=budget_guard_triggered,
                )

            ordered_calls = [assembled_calls[k] for k in sorted(assembled_calls.keys()) if assembled_calls[k].name]
            if not ordered_calls:
                return AgentLoopResult(
                    status="COMPLETED",
                    accumulated_text="".join(parts),
                    steps_executed=steps_executed,
                    tool_calls_count=tool_calls_count,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    loop_guard_triggered=loop_guard_triggered,
                    budget_guard_triggered=budget_guard_triggered,
                )

            # Record assistant tool_calls turn in loop history
            assistant_tool_calls = []
            for idx, call in enumerate(ordered_calls):
                call_id = call.id or f"call_step{step}_{idx}"
                call.id = call_id
                assistant_tool_calls.append(
                    {
                        "id": call_id,
                        "type": "function",
                        "function": {
                            "name": call.name,
                            "arguments": call.arguments_raw or "{}",
                        },
                    }
                )
            loop_messages.append(
                {
                    "role": "assistant",
                    "content": "".join(step_text_parts) or None,
                    "tool_calls": assistant_tool_calls,
                }
            )

            # Separate normal calls vs detected infinite-loop repeats
            batch_to_run: list[tuple[str, str, str]] = []
            repeated_ids: set[str] = set()

            for call in ordered_calls:
                tool_calls_count += 1
                tool_def = self._tools.get(call.name)
                source = tool_def.source if tool_def is not None else "builtin"
                try:
                    args_obj = json.loads(call.arguments_raw) if call.arguments_raw.strip() else {}
                    if not isinstance(args_obj, dict):
                        args_obj = {"raw": call.arguments_raw}
                except (json.JSONDecodeError, TypeError, ValueError):
                    args_obj = {"raw": call.arguments_raw}

                await emit(
                    "tool_call",
                    {
                        "call_id": call.id,
                        "tool_name": call.name,
                        "source": source,
                        "arguments": args_obj,
                        "step": step,
                    },
                )

                sig = _canonical_signature(call.name, call.arguments_raw)
                sig_counts[sig] = sig_counts.get(sig, 0) + 1
                if sig_counts[sig] >= self._config.max_identical_calls:
                    loop_guard_triggered = True
                    force_final = True
                    repeated_ids.add(call.id)
                else:
                    batch_to_run.append((call.id, call.name, call.arguments_raw))

            exec_results = await self._tools.execute_batch(batch_to_run)
            results_by_id = {r.call_id: r for r in exec_results}

            for call in ordered_calls:
                if call.id in repeated_ids:
                    guard_output = json.dumps(
                        {
                            "ok": False,
                            "error_type": "LOOP_DETECTED",
                            "message": f"检测到重复调用 `{call.name}` 且参数完全一致，已触发防死循环熔断。",
                            "hint": "请立即基于此前已获取的设定与前文结果直接撰写最终内容，不要再调用工具。",
                        },
                        ensure_ascii=False,
                    )
                    loop_messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call.id,
                            "name": call.name,
                            "content": guard_output,
                        }
                    )
                    await emit(
                        "tool_result",
                        {
                            "call_id": call.id,
                            "tool_name": call.name,
                            "source": "builtin",
                            "success": False,
                            "summary": "触发防死循环熔断（重复相同调用），强制进入最终撰写",
                            "latency_ms": 0,
                        },
                    )
                    continue

                res = results_by_id[call.id]
                bounded_output = self._context.offload_tool_output(res.tool_name, res.output)
                loop_messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": res.call_id,
                        "name": res.tool_name,
                        "content": bounded_output,
                    }
                )
                await emit(
                    "tool_result",
                    {
                        "call_id": res.call_id,
                        "tool_name": res.tool_name,
                        "source": res.source,
                        "success": res.success,
                        "summary": res.summary,
                        "latency_ms": res.latency_ms,
                    },
                )

            if total_in + total_out >= self._config.max_token_budget:
                budget_guard_triggered = True
                force_final = True
                loop_messages.append(
                    {
                        "role": "user",
                        "content": "【系统预算提示】本轮上下文 Token 预算已接近上限，请停止继续调用工具，直接输出完整小说正文。",
                    }
                )

        return AgentLoopResult(
            status="COMPLETED",
            accumulated_text="".join(parts),
            steps_executed=steps_executed,
            tool_calls_count=tool_calls_count,
            input_tokens=total_in,
            cached_tokens=total_cached,
            output_tokens=total_out,
            loop_guard_triggered=loop_guard_triggered,
            budget_guard_triggered=budget_guard_triggered,
        )
