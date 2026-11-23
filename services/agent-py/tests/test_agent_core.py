import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import pytest

from inkflow_agent.agent.context import ContextBudget, ContextManager, TokenCounter
from inkflow_agent.agent.loop import AgentLoopConfig, ReActAgentLoop
from inkflow_agent.agent.mcp_client import McpLoreClient
from inkflow_agent.agent.skills import SkillRegistry
from inkflow_agent.agent.tools import ToolDefinition, ToolRegistry, register_builtin_novel_tools
from inkflow_agent.streaming.llm_client import LlmStreamChunk, ToolCallDelta


class ScriptedToolLlm:
    def __init__(self, turns: list[list[LlmStreamChunk]]) -> None:
        self._turns = list(turns)
        self.seen_messages: list[list[dict[str, Any]]] = []
        self.seen_tools: list[list[dict[str, Any]] | None] = []

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[LlmStreamChunk]:
        self.seen_messages.append(messages)
        self.seen_tools.append(tools)
        for chunk in self._turns.pop(0):
            yield chunk


@pytest.mark.asyncio
async def test_mcp_stdio_server_discovers_all_lore_tools() -> None:
    async with McpLoreClient(
        biz_java_url="http://127.0.0.1:19999",
        internal_token="test-token",
        owner_id="00000000-0000-0000-0000-000000000001",
        book_id="00000000-0000-0000-0000-000000000002",
    ) as client:
        tools = await client.list_tools()

    names = {t.name for t in tools}
    assert {
        "get_character_state",
        "search_worldbook",
        "list_open_foreshadowings",
        "upsert_character_state",
    }.issubset(names)


@pytest.mark.asyncio
async def test_skill_progressive_disclosure_and_tool_self_healing() -> None:
    skills = SkillRegistry()
    assert {"combat-scene", "foreshadowing-craft", "ancient-dialogue", "plot-unblock"}.issubset(
        set(skills.available_names())
    )
    catalog = skills.render_catalog_prompt()
    assert "combat-scene" in catalog
    assert len(catalog) < 800  # lightweight catalog stays compact

    registry = ToolRegistry()
    register_builtin_novel_tools(
        registry,
        skill_registry=skills,
        biz_java_url="http://127.0.0.1:19999",
        internal_token="tok",
        owner_id="",
        book_id="",
        run_id="r1",
    )

    # Valid call loads full craft body
    ok_res = await registry.execute_one(
        call_id="c1",
        tool_name="load_writing_skill",
        raw_arguments='{"skill_name": "combat-scene"}',
    )
    assert ok_res.success is True
    assert "三段式动作节拍" in ok_res.output

    # Bad JSON & missing required field return structured self-healing hints instead of raising
    bad_json = await registry.execute_one(
        call_id="c2", tool_name="load_writing_skill", raw_arguments="{not-json"
    )
    assert bad_json.success is False
    assert json.loads(bad_json.output)["error_type"] == "INVALID_JSON_ARGUMENTS"

    bad_schema = await registry.execute_one(
        call_id="c3", tool_name="load_writing_skill", raw_arguments="{}"
    )
    assert bad_schema.success is False
    assert json.loads(bad_schema.output)["error_type"] == "VALIDATION_ERROR"


def test_context_manager_offloading_compression_and_kv_cache_prefix() -> None:
    cm = ContextManager(
        budget=ContextBudget(
            max_context_tokens=1600,
            reserved_output_tokens=400,
            max_tool_output_chars=200,
            keep_recent_messages=2,
        ),
        counter=TokenCounter(),
    )

    huge = "玄铁剑秘闻：" + ("剑气如虹。" * 120)
    offloaded = cm.offload_tool_output("search_worldbook", huge)
    assert len(offloaded) < len(huge)
    assert cm.stats.offloaded_observations == 1
    assert "obs_" in offloaded

    loop_history: list[dict[str, Any]] = []
    for i in range(12):
        loop_history.append(
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": f"call_{i}",
                        "type": "function",
                        "function": {"name": "search_worldbook", "arguments": f'{{"keyword":"词条{i}"}}'},
                    }
                ],
            }
        )
        loop_history.append(
            {
                "role": "tool",
                "tool_call_id": f"call_{i}",
                "name": "search_worldbook",
                "content": f"返回设定内容 {i}：" + ("星渊古宗禁地规则详述。" * 25),
            }
        )

    static_sys = "STATIC_PREFIX_MUST_STAY_FIRST"
    messages = cm.build_messages(
        static_system_prompt=static_sys,
        initial_user_prompt="请继续撰写第一章",
        loop_messages=loop_history,
    )

    # KV Cache invariant: static system prompt remains untouched at index 0
    assert messages[0] == {"role": "system", "content": static_sys}
    assert cm.stats.compressed_turns > 0
    assert any("历史工具调用与推理压缩摘要" in str(m.get("content", "")) for m in messages)


@pytest.mark.asyncio
async def test_react_agent_loop_parallel_tools_and_infinite_loop_guard() -> None:
    registry = ToolRegistry()

    async def fake_char(args: dict[str, Any]) -> str:
        return json.dumps({"name": args.get("name"), "realm": "筑基中期", "injury": "左臂带伤"}, ensure_ascii=False)

    registry.register(
        ToolDefinition(
            name="get_character_state",
            description="查角色状态",
            parameters_schema={"type": "object", "properties": {"name": {"type": "string"}}},
            source="mcp",
            handler=fake_char,
        )
    )

    llm = ScriptedToolLlm(
        turns=[
            # Turn 1: emits streaming tool_call split across two chunks
            [
                LlmStreamChunk(reasoning_delta="先查主角林青当前状态"),
                LlmStreamChunk(
                    tool_call_deltas=[ToolCallDelta(index=0, id="tc_1", name="get_character_state", arguments_delta='{"na')]
                ),
                LlmStreamChunk(
                    tool_call_deltas=[ToolCallDelta(index=0, arguments_delta='me":"林青"}')],
                    input_tokens=100,
                    cached_tokens=50,
                    output_tokens=20,
                ),
            ],
            # Turn 2: attempts the exact same call again -> triggers infinite-loop guard!
            [
                LlmStreamChunk(
                    tool_call_deltas=[ToolCallDelta(index=0, id="tc_2", name="get_character_state", arguments_delta='{"name": "林青"}')],
                    input_tokens=150,
                    cached_tokens=100,
                    output_tokens=18,
                ),
            ],
            # Turn 3: tools disabled by loop guard -> outputs final prose cleanly
            [
                LlmStreamChunk(content_delta="林青强忍左臂剧痛，筑基中期灵力骤然爆发。"),
                LlmStreamChunk(input_tokens=200, cached_tokens=150, output_tokens=25),
            ],
        ]
    )

    emitted: list[tuple[str, dict[str, Any]]] = []

    async def emit(ev_type: str, payload: dict[str, Any]) -> str:
        emitted.append((ev_type, payload))
        return f"ev-{len(emitted)}"

    ctrl_event = asyncio.Event()

    async def no_ctrl() -> None:
        return None

    loop = ReActAgentLoop(
        llm_client=llm,  # type: ignore[arg-type]
        tool_registry=registry,
        context_manager=ContextManager(),
        config=AgentLoopConfig(max_steps=6, max_identical_calls=2),
    )

    res = await loop.run(
        model_alias="writer-main",
        static_system_prompt="系统前缀",
        initial_user_prompt="续写林青出手段落",
        emit=emit,
        control_event=ctrl_event,
        check_control=no_ctrl,
    )

    assert res.status == "COMPLETED"
    assert res.steps_executed == 3
    assert res.loop_guard_triggered is True
    assert res.accumulated_text == "林青强忍左臂剧痛，筑基中期灵力骤然爆发。"
    assert llm.seen_tools[2] is None  # Tools disabled on forced final turn!
    assert [t for t, _ in emitted] == [
        "thought_delta",
        "tool_call",
        "tool_result",
        "tool_call",
        "tool_result",
        "text_delta",
    ]
