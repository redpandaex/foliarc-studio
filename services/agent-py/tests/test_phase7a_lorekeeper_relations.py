from __future__ import annotations

import asyncio
from typing import Any, Self

import httpx
import pytest

from foliarc_agent.agent.multi_agent import (
    EditorialBlackboard,
    EditorialTeamOrchestrator,
    InMemoryCheckpointStore,
)
from foliarc_agent.agent.router import IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings


def _rel(
    rel_id: str,
    src: str,
    dst: str,
    rel_type: str,
    *,
    narrative_order: int = 0,
    since_chapter: int = 1,
    source: str = "AUTHOR",
    desc: str = "",
) -> dict[str, Any]:
    return {
        "id": rel_id,
        "sourceCharacter": src,
        "targetCharacter": dst,
        "relationType": rel_type,
        "description": desc or f"{src}-{rel_type}-{dst}",
        "narrativeOrder": narrative_order,
        "sinceChapterNo": since_chapter,
        "source": source,
    }


def test_prioritize_relations_prefers_focus_characters_recency_and_author_authority() -> None:
    board = EditorialBlackboard(
        run_id="run-7a-1",
        owner_id="owner-1",
        book_id="book-1",
        chapter_no=13,
        pov_character="青儿",  # alias of 林青
        previous_chapters_context="第12章结尾：顾三与老渡在渡口暗室密议。",
        foreshadowing_writer_pack="[HINT #F03 残缺渡牌] 表面线索：木牌背面龙纹微热。",
    )

    relations: list[dict[str, Any]] = [
        # 12 noise relations (0 focus hits, older chapters) placed FIRST to verify old [:8] truncation is replaced
        *[
            _rel(f"noise-{i}", f"路人甲{i}", f"路人乙{i}", "认识", narrative_order=1000 + i, since_chapter=1)
            for i in range(1, 13)
        ],
        # Same-tier AUTHOR vs AI_EXTRACTED tie-break (both 2 focus hits, same narrativeOrder)
        _rel("r-ai-tie", "林青", "舟师", "AI同行", narrative_order=12002, since_chapter=12, source="AI_EXTRACTED"),
        _rel("r-author-tie", "林青", "舟师", "结盟", narrative_order=12002, since_chapter=12, source="AUTHOR"),
        # Older 2-hit relation (still beats 1-hit recent relations!)
        _rel("r-2hit-old", "林青", "顾三", "旧识", narrative_order=4001, since_chapter=4, source="AUTHOR"),
        # 1-hit relations with different recency (sinceChapterNo fallback when narrativeOrder == 0)
        _rel("r-1hit-recent", "舟师", "未知客", "警惕", narrative_order=0, since_chapter=12, source="AUTHOR"),
        _rel("r-1hit-older", "林青", "掌门", "师徒", narrative_order=2001, since_chapter=2, source="AUTHOR"),
        # 0-hit recent relation
        _rel("r-0hit-recent", "东海王", "北境将", "对峙", narrative_order=11005, since_chapter=11, source="AUTHOR"),
    ]

    mem = {
        "characters": [
            {"name": "林青", "aliases": "青儿"},
            {"name": "舟师", "aliases": "老渡"},
            {"name": "顾三", "aliases": "顾行舟"},
        ],
        "foreshadowings": [
            {"code": "#F03", "title": "残缺渡牌", "status": "PLANTED", "involvedCharacters": "林青,舟师"},
        ],
        "timelineEvents": [
            {"chapterNo": 12, "eventTitle": "渡口夜谈", "involvedCharacters": "顾三,老渡"},
        ],
        "relations": relations,
    }

    selected = EditorialTeamOrchestrator._prioritize_relations(board, mem, limit=12)
    selected_ids = [r["id"] for r in selected]

    assert len(selected) == 12
    # 2-hit tier first: recency DESC, then AUTHOR before AI_EXTRACTED
    assert selected_ids[0] == "r-author-tie"
    assert selected_ids[1] == "r-ai-tie"
    assert selected_ids[2] == "r-2hit-old"
    # 1-hit tier next: Ch.12 (12000 via sinceChapterNo) before Ch.2 (2001)
    assert selected_ids[3] == "r-1hit-recent"
    assert selected_ids[4] == "r-1hit-older"
    # 0-hit tier sorted by recency DESC
    assert selected_ids[5] == "r-0hit-recent"


def test_run_lorekeeper_injects_top_12_prioritized_relations_and_emits_hit_ids(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    relations = [
        *[_rel(f"bg-{i}", f"甲{i}", f"乙{i}", "旧闻", narrative_order=1000 + i, since_chapter=1) for i in range(1, 14)],
        _rel("focus-1", "林青", "舟师", "结盟", narrative_order=12002, since_chapter=12, source="AUTHOR"),
        _rel("focus-2", "舟师", "苏晚", "托付", narrative_order=11001, since_chapter=11, source="AUTHOR"),
    ]
    payload = {
        "characters": [
            {"name": "林青", "aliases": "青儿"},
            {"name": "舟师", "aliases": ""},
            {"name": "苏晚", "aliases": ""},
        ],
        "foreshadowings": [
            {"code": "#F03", "title": "残缺渡牌", "status": "PLANTED", "involvedCharacters": "林青,舟师"},
        ],
        "timelineEvents": [
            {"chapterNo": 12, "eventTitle": "黑水渡口立约", "summary": "双方结盟", "involvedCharacters": "林青,舟师"},
        ],
        "relations": relations,
    }

    class _FakeResponse:
        status_code = 200

        def json(self) -> dict[str, Any]:
            return payload

    class _FakeAsyncClient:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            pass

        async def __aenter__(self) -> Self:
            return self

        async def __aexit__(self, *args: object) -> None:
            return None

        async def get(self, *args: Any, **kwargs: Any) -> _FakeResponse:
            return _FakeResponse()

    monkeypatch.setattr(httpx, "AsyncClient", _FakeAsyncClient)

    settings = Settings()
    orchestrator = EditorialTeamOrchestrator(
        settings=settings,
        llm_client=None,  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=settings),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )
    board = EditorialBlackboard(
        run_id="run-7a-2",
        owner_id="owner-1",
        book_id="book-1",
        chapter_no=13,
        pov_character="林青",
    )

    events: list[tuple[str, dict[str, Any], str]] = []

    async def fake_emit(event_type: str, data: dict[str, Any], agent: str) -> str:
        events.append((event_type, data, agent))
        return f"evt-{len(events)}"

    brief, _ = asyncio.run(orchestrator._run_lorekeeper(board, fake_emit))

    assert "【人物关系网】" in brief
    assert "林青 -[结盟]-> 舟师" in brief
    assert "舟师 -[托付]-> 苏晚" in brief
    # Focus relations appear before background noise
    assert brief.index("林青 -[结盟]-> 舟师") < brief.index("舟师 -[托付]-> 苏晚")

    mem_results = [data for evt, data, _ in events if evt == "tool_result" and data.get("call_id") == "lore-structured-mem"]
    assert len(mem_results) == 1
    assert mem_results[0]["hit_relation_ids"][:2] == ["focus-1", "focus-2"]
    assert len(mem_results[0]["hit_relation_ids"]) == 12
