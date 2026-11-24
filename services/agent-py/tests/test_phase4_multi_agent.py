import asyncio
from collections.abc import AsyncIterator
from typing import Any

import pytest

from foliarc_agent.agent.multi_agent import (
    EditorialBlackboard,
    EditorialTeamOrchestrator,
    InMemoryCheckpointStore,
)
from foliarc_agent.agent.router import FallbackStreamClient, IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings
from foliarc_agent.streaming.llm_client import LlmStreamChunk, LlmUpstreamError


class _FakeStreamClient:
    """Scripted streaming LLM client for deterministic Multi-Agent & Fallback tests."""

    def __init__(self, script: list[str | Exception]) -> None:
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
        self.calls.append({"model_alias": model_alias, "messages": messages})
        item = self._script.pop(0)
        if isinstance(item, Exception):
            raise item
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
