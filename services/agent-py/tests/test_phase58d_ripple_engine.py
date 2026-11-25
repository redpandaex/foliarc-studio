from typing import Any

import pytest

from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider
from foliarc_agent.rag.memory_extractor import StructuredMemoryExtractor
from foliarc_agent.rag.ripple_engine import RippleEngine, validate_patch_hunks
from foliarc_agent.rag.vector_store import InMemoryVectorStore, VectorChunkRow
from foliarc_agent.rag.worker import RagIngestionWorker


@pytest.mark.asyncio
async def test_ripple_stage1_filters_typo_edits_and_extracts_substantive_fact_delta() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    engine = RippleEngine(settings)

    # Pure punctuation / 1-char typo -> NO_CHANGE
    no_change = await engine.extract_fact_deltas(
        old_content="夜雨淅沥，林青站在古寺山门前沉默不语，手中的青铜令隐隐发烫。",
        new_content="夜雨淅沥，林青站在古寺山门前沉默不语，手中的青铜令隐隐发烫！",
    )
    assert no_change["hasSubstantiveFactChange"] is False
    assert no_change["factDeltas"] == []

    # Substantive plot change: Second Elder changed from killed to escaped injured
    substantive = await engine.extract_fact_deltas(
        old_content="寒潭决战中，二长老被血魔一掌击毙，尸骨沉入寒潭。",
        new_content="寒潭决战中，二长老重伤遁逃，带着半卷剑谱潜入北岭。",
    )
    assert substantive["hasSubstantiveFactChange"] is True
    assert len(substantive["factDeltas"]) >= 1
    assert any("二长老" in d["entity"] or "二长老" in d["searchKeywords"] for d in substantive["factDeltas"])


@pytest.mark.asyncio
async def test_ripple_stage2_bidirectional_impact_radar_and_stage3_surgical_patches() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    engine = RippleEngine(settings)

    fact_deltas = [
        {
            "type": "ITEM_OWNERSHIP",
            "entity": "玄铁剑",
            "oldFact": "青云宗祖传玄铁剑",
            "newFact": "星渊古墓出土的断霄剑",
            "searchKeywords": ["玄铁剑", "青云宗祖传玄铁剑"],
        }
    ]
    candidates = [
        {
            "sectionId": "sec-ch2",
            "chapterNo": 2,
            "sectionNo": 1,
            "narrativeOrder": 2001,
            "chapterTitle": "山门试剑",
            "summaryText": "林青祭出青云宗祖传玄铁剑。",
            "content": "山门晨雾未散。\n林青缓缓拔出青云宗祖传玄铁剑，寒光映雪。\n围观弟子无不屏息。",
        },
        {
            "sectionId": "sec-ch12",
            "chapterNo": 12,
            "sectionNo": 1,
            "narrativeOrder": 12001,
            "chapterTitle": "北岭夜谈",
            "summaryText": "掌门提及玄铁剑来历。",
            "content": "炉火微明。\n掌门轻叹道：“你手里那柄青云宗祖传玄铁剑，本是开派祖师所铸。”\n林青垂眸不答。",
        },
        {
            "sectionId": "sec-ch15",
            "chapterNo": 15,
            "sectionNo": 1,
            "narrativeOrder": 15001,
            "chapterTitle": "集市风波",
            "summaryText": "街市偶遇玄铁剑买主。",
            "content": "茶楼喧闹，有人议论起林青腰间的玄铁剑。",
        },
    ]

    impacts, _, _ = await engine.evaluate_impacts(
        fact_deltas=fact_deltas,
        change_summary="更改主角佩剑来历",
        source_order=8001,
        candidates=candidates,
        genre_code="XIANXIA_FANTASY",
    )
    by_sec = {i["targetSectionId"]: i for i in impacts}
    assert by_sec["sec-ch2"]["direction"] == "BACKWARD"
    assert by_sec["sec-ch2"]["severity"] == "STRONG_CONFLICT"
    assert "前文" in by_sec["sec-ch2"]["conflictReason"]

    assert by_sec["sec-ch12"]["direction"] == "FORWARD"
    assert by_sec["sec-ch12"]["severity"] == "STRONG_CONFLICT"
    assert "后文" in by_sec["sec-ch12"]["conflictReason"]

    assert by_sec["sec-ch15"]["direction"] == "FORWARD"
    assert by_sec["sec-ch15"]["severity"] == "MINOR_MENTION"

    # Stage 3: Surgical paragraph patches preserve unrelated paragraphs verbatim
    patches, _, _ = await engine.generate_surgical_patches(
        fact_deltas=fact_deltas,
        change_summary="更改主角佩剑来历",
        targets=[
            {
                "patchId": "p-ch2",
                "direction": "BACKWARD",
                "chapterNo": 2,
                "chapterTitle": "山门试剑",
                "conflictReason": by_sec["sec-ch2"]["conflictReason"],
                "currentContent": candidates[0]["content"],
            },
            {
                "patchId": "p-ch12",
                "direction": "FORWARD",
                "chapterNo": 12,
                "chapterTitle": "北岭夜谈",
                "conflictReason": by_sec["sec-ch12"]["conflictReason"],
                "currentContent": candidates[1]["content"],
            },
        ],
        genre_code="XIANXIA_FANTASY",
    )
    p2 = next(p for p in patches if p["patchId"] == "p-ch2")
    assert p2["status"] == "READY_FOR_REVIEW"
    assert len(p2["hunks"]) == 1
    assert p2["hunks"][0]["originalParagraph"] == "林青缓缓拔出青云宗祖传玄铁剑，寒光映雪。"
    assert "星渊古墓出土的断霄剑" in p2["hunks"][0]["patchedParagraph"]

    # Validation rejects non-matching originalParagraph
    ok, _ = validate_patch_hunks("真实正文段落", [{"originalParagraph": "不存在的段落", "patchedParagraph": "改写"}])
    assert ok is False


@pytest.mark.asyncio
async def test_worker_ripple_analyze_and_patch_pipeline_respects_range_and_excludes_source() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="")
    embedder = EmbeddingProvider(settings)
    store = InMemoryVectorStore()

    # Seed Milvus chunks: ch 5 (in range), ch 8 (source, must be excluded), ch 12 (in range), ch 60 (out of range!)
    vecs = await embedder.embed_texts(
        [
            "第5章：二长老在宗门议事时提出异议。",
            "第8章：二长老重伤遁逃。",
            "第12章：长老堂宣称二长老已经战死寒潭，众弟子哀悼。",
            "第60章：多年后依然传闻二长老已经战死寒潭。",
        ]
    )
    await store.upsert_chunks(
        [
            VectorChunkRow("c5", "book-1", "owner-1", "doc-5", 1, "p5", 5, "CHAPTER", "第5章", "第5章：二长老已经战死寒潭的预言。", vecs[0], "BOOK", 5001, "sec-5"),
            VectorChunkRow("c8", "book-1", "owner-1", "doc-8", 1, "p8", 8, "CHAPTER", "第8章", "第8章：二长老已经战死寒潭。", vecs[1], "BOOK", 8001, "sec-8"),
            VectorChunkRow("c12", "book-1", "owner-1", "doc-12", 1, "p12", 12, "CHAPTER", "第12章", "第12章：长老堂宣称二长老已经战死寒潭，众弟子哀悼。", vecs[2], "BOOK", 12001, "sec-12"),
            VectorChunkRow("c60", "book-1", "owner-1", "doc-60", 1, "p60", 60, "CHAPTER", "第60章", "第60章：多年后依然传闻二长老已经战死寒潭。", vecs[3], "BOOK", 60001, "sec-60"),
        ]
    )

    worker = RagIngestionWorker(
        settings=settings,
        vector_store=store,
        embedder=embedder,
        memory_extractor=StructuredMemoryExtractor(settings),
        ripple_engine=RippleEngine(settings),
    )

    reported_analysis: list[dict[str, Any]] = []
    reported_patches: list[dict[str, Any]] = []

    async def fake_ctx(_job_id: str) -> dict[str, Any]:
        return {
            "jobId": "job-1",
            "bookId": "book-1",
            "ownerId": "owner-1",
            "genreCode": "XIANXIA_FANTASY",
            "sourceSectionId": "sec-8",
            "sourceChapterNo": 8,
            "sourceNarrativeOrder": 8001,
            "rangeMode": "BIDIRECTIONAL",
            "rangeStartChapter": 1,
            "rangeEndChapter": 20,
            "rangeStartOrder": 1001,
            "rangeEndOrder": 20999,
            "maxCandidatesCap": 20,
            "oldContent": "二长老已经战死寒潭。",
            "newContent": "二长老重伤逃往北岭。",
            "changeNote": "",
        }

    async def fake_candidates(**_kwargs: Any) -> dict[str, Any]:
        return {"scannedSectionCount": 18, "candidates": []}

    async def fake_report_analysis(_job_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        reported_analysis.append(payload)
        return payload

    async def fake_batch_targets(_job_id: str, _batch_no: int) -> dict[str, Any]:
        return {
            "jobId": "job-1",
            "batchNo": 1,
            "genreCode": "XIANXIA_FANTASY",
            "changeSummary": "二长老改为重伤逃走",
            "styleAnchorsText": "",
            "factDeltas": [
                {
                    "type": "CHARACTER_STATE",
                    "entity": "二长老",
                    "oldFact": "已经战死寒潭",
                    "newFact": "重伤逃往北岭",
                    "searchKeywords": ["二长老"],
                }
            ],
            "targets": [
                {
                    "patchId": "patch-12",
                    "direction": "FORWARD",
                    "chapterNo": 12,
                    "chapterTitle": "长老堂议事",
                    "conflictReason": "后文仍写战死",
                    "currentContent": "第一段不变。\n长老堂宣称二长老已经战死寒潭，众弟子哀悼。",
                }
            ],
        }

    async def fake_report_patch(_job_id: str, _batch_no: int, payload: dict[str, Any]) -> dict[str, Any]:
        reported_patches.append(payload)
        return payload

    worker._fetch_ripple_context = fake_ctx  # type: ignore[method-assign]
    worker._search_ripple_candidates = fake_candidates  # type: ignore[method-assign]
    worker._report_ripple_analysis = fake_report_analysis  # type: ignore[method-assign]
    worker._fetch_ripple_batch_targets = fake_batch_targets  # type: ignore[method-assign]
    worker._report_ripple_patch_batch = fake_report_patch  # type: ignore[method-assign]

    await worker.process_ripple_analyze_payload({"job_id": "job-1"})
    assert len(reported_analysis) == 1
    impact_ids = {i["targetSectionId"] for i in reported_analysis[0]["impacts"]}
    assert "sec-8" not in impact_ids  # source excluded
    assert "sec-60" not in impact_ids  # out of [1, 20] range excluded!
    assert "sec-5" in impact_ids or "sec-12" in impact_ids

    await worker.process_ripple_patch_payload({"job_id": "job-1", "batch_no": 1})
    assert len(reported_patches) == 1
    assert reported_patches[0]["patches"][0]["status"] == "READY_FOR_REVIEW"
    assert "重伤逃往北岭" in reported_patches[0]["patches"][0]["hunks"][0]["patchedParagraph"]
