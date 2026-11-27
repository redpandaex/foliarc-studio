import asyncio
from collections.abc import AsyncIterator
from typing import Any

import pytest

from foliarc_agent.agent.multi_agent import (
    EditorialBlackboard,
    EditorialTeamOrchestrator,
    InMemoryCheckpointStore,
    _parse_critic_json,
)
from foliarc_agent.agent.router import FallbackStreamClient, IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings
from foliarc_agent.streaming.llm_client import LlmStreamChunk, LlmUpstreamError


class _FakeStreamClient:
    """Scripted streaming LLM client for deterministic Multi-Agent & Fallback tests."""

    def __init__(self, script: list[str | list[LlmStreamChunk] | Exception]) -> None:
        self._script = list(script)
        self.calls: list[dict[str, Any]] = []

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 0.8,
        max_tokens: int = 2048,
    ) -> AsyncIterator[LlmStreamChunk]:
        self.calls.append({"model_alias": model_alias, "messages": messages, "max_tokens": max_tokens})
        item = self._script.pop(0)
        if isinstance(item, Exception):
            raise item
        if isinstance(item, list):
            for chunk in item:
                yield chunk
            return
        yield LlmStreamChunk(content_delta=item, input_tokens=100, cached_tokens=40, output_tokens=50)


@pytest.mark.asyncio
async def test_intent_and_model_router_rule_and_embedding_layers() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    router = IntentAndModelRouter(settings=settings)

    # Layer 1: Rule keyword hit -> COMBAT_SCENE + combat-scene skill + cost-tiered models
    r1 = await router.route(
        action="CONTINUE",
        instruction="安排主角在悬崖边与黑衣刺客展开激烈剑招打斗",
        requested_model_alias="writer-main",
    )
    assert r1.intent == "COMBAT_SCENE"
    assert r1.layer == "RULE"
    assert r1.recommended_skill == "combat-scene"
    assert r1.model_plan.director == "fast-router"
    assert r1.model_plan.writer == "writer-main"
    assert r1.model_plan.critic == "critic-reasoner"

    # Layer 2: Embedding semantic match when no exact keyword appears
    r2 = await router.route(
        action="CONTINUE",
        instruction="刻画古风门派两位长老言语试探和心理博弈",
        requested_model_alias="writer-main",
    )
    assert r2.layer in ("RULE", "EMBEDDING")
    assert r2.intent in ("ANCIENT_DIALOGUE", "STYLE_POLISH", "CHAPTER_COMPOSE")


@pytest.mark.asyncio
async def test_fallback_stream_client_failover_on_429() -> None:
    fake = _FakeStreamClient(
        [
            LlmUpstreamError("HTTP 429 rate limited", retryable=True, code="LLM_HTTP_429"),
            "降级模型成功生成正文",
        ]
    )
    client = FallbackStreamClient(fake)  # type: ignore[arg-type]
    fallbacks: list[tuple[str, str]] = []

    async def on_fb(src: str, dst: str, _reason: str) -> None:
        fallbacks.append((src, dst))

    out: list[str] = []
    async for chunk in client.stream_chat(
        model_alias="critic-reasoner",
        messages=[{"role": "user", "content": "test"}],
        on_fallback=on_fb,
    ):
        out.append(chunk.content_delta)

    assert "".join(out) == "降级模型成功生成正文"
    assert fallbacks == [("critic-reasoner", "writer-main")]
    assert [c["model_alias"] for c in fake.calls] == ["critic-reasoner", "writer-main"]


@pytest.mark.asyncio
async def test_multi_agent_hitl_checkpoint_and_critic_reflection_loop() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    checkpoints = InMemoryCheckpointStore()
    fake = _FakeStreamClient(
        [
            # 1. DIRECTOR outline
            "1. 寒潭遇袭：黑衣人潜伏松林\n2. 剑气交锋：林青拔出玄铁重剑迎敌\n3. 尾声钩子：发现黑衣人腰间青铜令",
            # 2. WRITER draft 1
            "第一稿草稿：林青在寒潭边随意一挥木剑击退敌人。",
            # 3. CRITIC rejects draft 1 (OOC / wrong weapon -> triggers reflection!)
            '{"passed": false, "score": 60, "issues": ["设定矛盾：主角使用的是玄铁重剑而非木剑"], "revision_guidance": "改为玄铁重剑并强化动作节奏"}',
            # 4. WRITER draft 2 (revised!)
            "第二稿终稿：寒潭雾气翻涌，林青反手抽出背后八十一斤玄铁重剑，星辰剑气横扫松林，逼退黑衣刺客，并夺下其腰间残缺青铜令。",
            # 5. CRITIC approves draft 2
            '{"passed": true, "score": 92, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=checkpoints,
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
        max_revisions=2,
    )

    events: list[tuple[str, str, dict[str, Any]]] = []

    async def emit(ev_type: str, payload: dict[str, Any], agent: str = "writer") -> str:
        events.append((ev_type, agent, payload))
        return f"ev-{len(events)}"

    ctrl_ev = asyncio.Event()

    async def check_ctrl() -> None:
        return None

    # Phase A: Run until HITL OUTLINE_APPROVAL interrupt
    board = EditorialBlackboard(
        run_id="run-ma-1",
        book_title="星辰剑录",
        chapter_no=3,
        chapter_title="寒潭剑影",
        instruction="安排一场打斗戏并埋下青铜令线索",
        require_outline_approval=True,
    )
    res1 = await orchestrator.run(
        initial_state=board,
        emit=emit,
        control_event=ctrl_ev,
        check_control=check_ctrl,
        resumed=False,
    )
    assert res1.status == "INTERRUPTED"
    assert res1.interrupt_type == "OUTLINE_APPROVAL"
    assert res1.current_node == "DIRECTOR"
    assert res1.route_intent == "COMBAT_SCENE"
    assert res1.interrupt_payload is not None
    assert len(res1.interrupt_payload["beats"]) == 3

    # Verify PostgreSQL checkpoint saved for ROUTER + DIRECTOR(INTERRUPTED)
    saved_nodes_1 = [(n, st) for _, n, st, _ in checkpoints.records["run-ma-1"]]
    assert saved_nodes_1 == [("ROUTER", "COMPLETED"), ("DIRECTOR", "INTERRUPTED")]

    # Phase B: Human edits outline & approves -> resumes from PG checkpoint -> Lorekeeper -> Writer -> Critic -> Writer -> Critic
    edited_outline = "1. 寒潭遇袭\n2. 玄铁重剑破阵\n3. 夺得青铜令残片（作者微调）"
    res2 = await orchestrator.run(
        initial_state=EditorialBlackboard(run_id="run-ma-1"),
        emit=emit,
        control_event=ctrl_ev,
        check_control=check_ctrl,
        resumed=True,
        approval_payload={"approved": True, "edited_outline": edited_outline, "feedback": ""},
    )
    assert res2.status == "COMPLETED"
    assert res2.revision_count == 1
    assert "八十一斤玄铁重剑" in res2.accumulated_text

    # Verify full checkpoint progression & reflection events
    saved_nodes_all = [n for _, n, _, _ in checkpoints.records["run-ma-1"]]
    assert saved_nodes_all == ["ROUTER", "DIRECTOR", "LOREKEEPER", "WRITER", "CRITIC", "WRITER", "CRITIC"]
    assert any(t == "draft_reset" for t, _, _ in events)
    assert any(t == "critic_review" and p["passed"] is False for t, _, p in events)
    assert any(t == "critic_review" and p["passed"] is True and p["score"] == 92 for t, _, p in events)


@pytest.mark.asyncio
async def test_previous_chapters_context_flows_to_director_writer_and_critic() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    checkpoints = InMemoryCheckpointStore()
    fake = _FakeStreamClient(
        [
            "1. 紧承第一章结尾：林青手持残缺青铜令退入古寺\n2. 黑衣人追至门外对峙",
            "雨声未歇，林青握紧刚从寒潭边拾起的残缺青铜令，闪身没入古寺破败的山门。",
            '{"passed": true, "score": 90, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=checkpoints,
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    async def emit(_ev_type: str, _payload: dict[str, Any], _agent: str = "writer") -> str:
        return "ev-1"

    async def check_ctrl() -> None:
        return None

    prev_ctx = "【上一章（第 1 章《寒潭遗令》）正文衔接】\n林青在寒潭边拾起残缺青铜令，身后的松林里忽有铁甲铿锵逼近。"
    board = EditorialBlackboard(
        run_id="run-ch2-continuity",
        book_title="星辰剑录",
        chapter_no=2,
        chapter_title="古寺避雨",
        chapter_context="",
        previous_chapters_context=prev_ctx,
        instruction="",
        require_outline_approval=False,
    )
    res = await orchestrator.run(
        initial_state=board,
        emit=emit,
        control_event=asyncio.Event(),
        check_control=check_ctrl,
        resumed=False,
    )
    assert res.status == "COMPLETED"
    assert len(fake.calls) == 3  # DIRECTOR, WRITER, CRITIC
    for call in fake.calls:
        combined = "\n".join(str(m.get("content") or "") for m in call["messages"])
        assert "残缺青铜令" in combined
        assert "铁甲铿锵逼近" in combined


@pytest.mark.asyncio
async def test_writer_empty_reasoning_truncation_auto_falls_back_to_writer_main() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    fake = _FakeStreamClient(
        [
            # 1. DIRECTOR outline
            "1. 藏经阁密室：林玄初次淬体\n2. 丹房后巷：暗伤经脉探查",
            # 2. WRITER on critic-reasoner exhausts tokens in reasoning_content -> empty content
            [
                LlmStreamChunk(
                    reasoning_delta="构思四个场景细节...",
                    content_delta="",
                    finish_reason="length",
                    input_tokens=180,
                    output_tokens=3200,
                )
            ],
            # 3. WRITER auto-fallback to writer-main produces real prose
            "夜雨敲打着丹房后巷的青瓦，林玄指尖微颤，顺着残图经脉节点稳住体内灵力。",
            # 4. CRITIC receives non-empty prose and approves
            '{"passed": true, "score": 90, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    events: list[tuple[str, str, dict[str, Any]]] = []

    async def emit(ev_type: str, payload: dict[str, Any], agent: str = "writer") -> str:
        events.append((ev_type, agent, payload))
        return f"ev-{len(events)}"

    async def check_ctrl() -> None:
        return None

    res = await orchestrator.run(
        initial_state=EditorialBlackboard(
            run_id="run-writer-fallback",
            book_title="丹道长生",
            chapter_no=2,
            chapter_title="雨夜探巷",
            requested_model_alias="critic-reasoner",
            require_outline_approval=False,
        ),
        emit=emit,
        control_event=asyncio.Event(),
        check_control=check_ctrl,
    )

    assert res.status == "COMPLETED"
    assert "夜雨敲打着丹房后巷的青瓦" in res.accumulated_text
    assert [c["model_alias"] for c in fake.calls] == [
        "fast-router",
        "critic-reasoner",
        "writer-main",
        "critic-reasoner",
    ]
    critic_user_msg = str(fake.calls[3]["messages"][1]["content"])
    assert "夜雨敲打着丹房后巷的青瓦" in critic_user_msg
    assert any(t == "thought_delta" and "正文空稿保护" in str(p.get("content", "")) for t, _, p in events)


@pytest.mark.asyncio
async def test_critic_truncated_reasoning_falls_back_and_never_false_passes() -> None:
    assert _parse_critic_json("", draft_text="有正文", finish_reason="length")["passed"] is False
    assert _parse_critic_json('{"passed": true, "score": 95}', draft_text="")["passed"] is False

    settings = Settings(llm_api_key="", embedding_api_key="")
    fake = _FakeStreamClient(
        [
            "1. 古寺避雨：林青查探青铜令",
            "林青吹灭油灯，将残缺青铜令收入袖中。",
            # CRITIC on critic-reasoner truncates inside reasoning_content with empty content
            [
                LlmStreamChunk(
                    reasoning_delta="在【待审稿正文】之后检查...",
                    content_delta="",
                    finish_reason="length",
                    input_tokens=150,
                    output_tokens=1200,
                )
            ],
            # CRITIC auto-fallback to writer-main outputs valid JSON
            '{"passed": true, "score": 88, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    events: list[tuple[str, str, dict[str, Any]]] = []

    async def emit(ev_type: str, payload: dict[str, Any], agent: str = "writer") -> str:
        events.append((ev_type, agent, payload))
        return f"ev-{len(events)}"

    async def check_ctrl() -> None:
        return None

    res = await orchestrator.run(
        initial_state=EditorialBlackboard(
            run_id="run-critic-fallback",
            book_title="星辰剑录",
            chapter_no=2,
            chapter_title="古寺避雨",
            require_outline_approval=False,
        ),
        emit=emit,
        control_event=asyncio.Event(),
        check_control=check_ctrl,
    )

    assert res.status == "COMPLETED"
    assert [c["model_alias"] for c in fake.calls] == [
        "fast-router",
        "writer-main",
        "critic-reasoner",
        "writer-main",
    ]
    assert any(t == "critic_review" and p["score"] == 88 and p["passed"] is True for t, _, p in events)


@pytest.mark.asyncio
async def test_writer_empty_after_fallback_raises_llm_empty_draft() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    fake = _FakeStreamClient(
        [
            "1. 藏经阁密室：林玄初次淬体",
            [LlmStreamChunk(reasoning_delta="thinking...", content_delta="", finish_reason="length")],
            [LlmStreamChunk(content_delta="   ", finish_reason="stop")],
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    async def emit(_ev_type: str, _payload: dict[str, Any], _agent: str = "writer") -> str:
        return "ev-1"

    async def check_ctrl() -> None:
        return None

    with pytest.raises(LlmUpstreamError) as exc_info:
        await orchestrator.run(
            initial_state=EditorialBlackboard(
                run_id="run-writer-both-empty",
                book_title="丹道长生",
                chapter_no=2,
                requested_model_alias="critic-reasoner",
                require_outline_approval=False,
            ),
            emit=emit,
            control_event=asyncio.Event(),
            check_control=check_ctrl,
        )

    assert exc_info.value.code == "LLM_EMPTY_DRAFT"
    assert len(fake.calls) == 3  # DIRECTOR + WRITER + WRITER fallback; CRITIC never called!
