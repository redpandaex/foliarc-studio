import asyncio
from collections.abc import AsyncIterator
from typing import Any

import pytest

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry
from foliarc_agent.agent.multi_agent import (
    EditorialBlackboard,
    EditorialTeamOrchestrator,
    InMemoryCheckpointStore,
)
from foliarc_agent.agent.router import FallbackStreamClient, IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings
from foliarc_agent.rag.distiller import ChapterDistiller
from foliarc_agent.streaming.llm_client import LlmStreamChunk, build_novel_messages


class _RecordingStreamClient:
    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
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
        text = self._responses.pop(0)
        yield LlmStreamChunk(content_delta=text, input_tokens=120, cached_tokens=80, output_tokens=60)


def test_tiered_skill_catalog_filters_by_genre_and_cross_genre_sub_tags() -> None:
    registry = SkillRegistry()
    all_names = set(registry.available_names())
    assert {
        "foreshadowing-craft",
        "plot-unblock",
        "dialogue-subtext",
        "combat-scene",
        "ancient-dialogue",
        "horror-atmosphere",
        "rule-horror-logic",
        "classical-prose",
        "fair-play-clues",
        "scifi-hard-logic",
        "urban-tension",
    }.issubset(all_names)

    # Pure Classical book: must include classical-prose & ancient-dialogue, must NOT include combat-scene or horror-atmosphere
    classical = set(registry.available_names(genre_code="CLASSICAL_HISTORICAL"))
    assert "classical-prose" in classical
    assert "ancient-dialogue" in classical
    assert "foreshadowing-craft" in classical
    assert "combat-scene" not in classical
    assert "horror-atmosphere" not in classical

    # Cross-genre Classical + sub_genre_tags="民俗恐怖": unlocks BOTH classical-prose AND horror-atmosphere!
    cross = set(
        registry.available_names(
            genre_code="CLASSICAL_HISTORICAL",
            sub_genre_tags="明清世情,民俗恐怖",
        )
    )
    assert "classical-prose" in cross
    assert "horror-atmosphere" in cross
    assert "combat-scene" not in cross


@pytest.mark.asyncio
async def test_genre_aware_router_blocks_wrong_genre_skills_and_routes_horror_rules() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    router = IntentAndModelRouter(settings=settings, skill_registry=SkillRegistry())

    # Classical book mentioning a scuffle must NEVER trigger combat-scene
    r_classical = await router.route(
        action="CONTINUE",
        instruction="府中仆役打斗推搡，引出宗族礼法与世情暗涌",
        genre_code="CLASSICAL_HISTORICAL",
    )
    assert r_classical.recommended_skill != "combat-scene"
    assert r_classical.recommended_skill == "classical-prose"

    # Horror book mentioning rules triggers rule-horror-logic
    r_horror = await router.route(
        action="CONTINUE",
        instruction="主角在值班室墙上发现三条泛黄守则与禁忌条文",
        genre_code="HORROR_SUPERNATURAL",
    )
    assert r_horror.intent == "RULE_HORROR"
    assert r_horror.recommended_skill == "rule-horror-logic"

    # Cross-genre Classical + 民俗恐怖 unlocks horror-atmosphere
    r_cross = await router.route(
        action="CONTINUE",
        instruction="夜雨荒祠阴森诡异，纸人无风自动",
        genre_code="CLASSICAL_HISTORICAL",
        sub_genre_tags="明清世情,民俗恐怖",
    )
    assert r_cross.recommended_skill == "horror-atmosphere"


@pytest.mark.asyncio
async def test_horror_profile_and_style_anchors_flow_to_director_writer_and_critic() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    fake = _RecordingStreamClient(
        [
            "1. 夜班走廊灯光闪动\n2. 门外脚步声停在第三声\n3. 假安全后发现镜中倒影慢了半拍",
            "日光灯管发出细碎嗡鸣，林远垂眼看向门缝下静止的水渍，没有抬头。",
            '{"passed": true, "score": 91, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings, skill_registry=SkillRegistry()),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    async def emit(_ev: str, _payload: dict[str, Any], _agent: str = "writer") -> str:
        return "ev-1"

    async def check_ctrl() -> None:
        return None

    anchor = "【锚点 1 · 深夜值班室】\n荧光灯在头顶轻响，茶杯沿凝了一圈冷雾，走廊尽头的防火门比昨夜多开了半指。"
    board = EditorialBlackboard(
        run_id="run-58c-horror",
        book_title="四号楼值班守则",
        genre="恐怖 / 灵异 / 规则怪谈",
        genre_code="HORROR_SUPERNATURAL",
        sub_genre_tags="规则怪谈,心理惊悚",
        style_anchors_text=anchor,
        chapter_no=5,
        chapter_title="第三声敲门",
        instruction="写阴森诡异的深夜巡楼段落",
        require_outline_approval=False,
    )
    res = await orchestrator.run(
        initial_state=board,
        emit=emit,
        control_event=asyncio.Event(),
        check_control=check_ctrl,
    )
    assert res.status == "COMPLETED"
    assert len(fake.calls) == 3

    director_text = "\n".join(str(m["content"]) for m in fake.calls[0]["messages"])
    writer_sys = str(fake.calls[1]["messages"][0]["content"])
    critic_sys = str(fake.calls[2]["messages"][0]["content"])

    assert "假安全后的二次反转" in director_text
    assert "严禁使用“令人毛骨悚然”" in writer_sys
    assert "四号楼值班守则" in writer_sys
    assert "比昨夜多开了半指" in writer_sys
    assert "规则逻辑闭环" in critic_sys
    assert "战力境界" not in critic_sys

    # Direct mode also embeds genre anti-cliche & style anchors in static system prefix
    direct_msgs = build_novel_messages(
        action="CONTINUE",
        book_title="四号楼值班守则",
        genre="恐怖 / 灵异 / 规则怪谈",
        genre_code="HORROR_SUPERNATURAL",
        sub_genre_tags="规则怪谈",
        style_anchors_text=anchor,
        synopsis=None,
        chapter_title="第三声敲门",
        chapter_context="",
        instruction="继续推进",
    )
    assert "严禁使用“令人毛骨悚然”" in direct_msgs[0]["content"]
    assert "比昨夜多开了半指" in direct_msgs[0]["content"]


def test_distiller_prompt_includes_genre_custom_attribute_schema() -> None:
    profile = GenreProfileRegistry.resolve("CLASSICAL_HISTORICAL")
    prompt = ChapterDistiller._build_distill_user_prompt(
        chapter_no=4,
        section_no=1,
        chapter_title="雪夜访客",
        section_title="雪夜访客",
        volume_arc_goal="姑苏盐案初现端倪",
        prev_ending_hook="门房递上一封无署名的青笺",
        known_characters=["沈知微"],
        open_foreshadowings=[],
        genre=profile.display_name,
        genre_code="CLASSICAL_HISTORICAL",
        body_block="沈知微拂去袖上残雪，接过青笺细看。",
    )
    assert "courtesyName(字 / 号)" in prompt
    assert "officialTitle(官职爵位)" in prompt
    assert "礼法暗示" in prompt
