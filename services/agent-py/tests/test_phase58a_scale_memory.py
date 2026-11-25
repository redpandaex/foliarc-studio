from __future__ import annotations

import pytest

from foliarc_agent.config import Settings
from foliarc_agent.rag.distiller import ChapterDistiller, split_logical_segments
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.vector_store import (
    InMemorySummaryVectorStore,
    InMemoryVectorStore,
    SummaryVectorRow,
    VectorChunkRow,
    build_multiscope_filter_expr,
)
from foliarc_agent.streaming.llm_client import build_novel_messages


@pytest.mark.asyncio
async def test_ultra_long_chapter_map_reduce_distillation_and_zero_truncation() -> None:
    settings = Settings(llm_api_key="")
    distiller = ChapterDistiller(settings)

    # Simulate a 30,000-char classical/literary big chapter with distinct early, middle, and ending beats
    part_a = "第一幕：地下室油灯摇曳，林青在雨夜翻开泛黄日记，立下绝不向旧秩序低头的誓言。\n" * 160
    part_b = "第二幕：雪夜酒馆冲突爆发，苏晚当众扯断青铜令绳结，揭穿伪善长老的交易账簿。\n" * 160
    part_c = "第三幕终局尾声：钟楼十二声敲响，林青握紧玄铁断剑踏入迷雾深处，身后传来沉重锁链拖地声。"
    long_content = part_a + "\n\n" + part_b + "\n\n" + part_c
    assert len(long_content) > 12_000

    segments = split_logical_segments(long_content, segment_size=5000, overlap=200)
    assert len(segments) >= 3

    result = await distiller.distill_section(
        chapter_no=1,
        section_no=1,
        chapter_title="地下室长夜",
        section_title="全篇",
        content=long_content,
        known_characters=["林青", "苏晚"],
    )
    assert result["meta"]["usedMapReduce"] is True
    assert result["meta"]["segmentCount"] >= 3
    assert "锁链拖地声" in result["summary"]["endingHook"]
    assert "林青" in result["summary"]["involvedCharacters"]
    assert len(result["memory"]["characterUpdates"]) >= 2

    # Verify prose-sampled auto-charter detects first-person POV from actual prose excerpt
    charter = await distiller.infer_story_charter(
        book_title="地下室手记续编",
        genre="古典文学",
        existing_synopsis=None,
        first_section_sample="我是一个有病的人……我是一个心怀恶意的人。我一点也不讨人喜欢。我相信我的肝有毛病。",
        latest_section_sample="我推开冰冷的木门，望着漫天鹅毛大雪。",
        known_characters=["地下室人"],
    )
    assert "第一人称" in charter
    assert "核心主线" in charter


@pytest.mark.asyncio
async def test_logarithmic_tier1_tier2_tier3_rollups() -> None:
    settings = Settings(llm_api_key="")
    distiller = ChapterDistiller(settings)

    sec_items = [
        {
            "chapterNo": i,
            "label": f"第{i}章",
            "summaryText": f"第{i}章推进主线冲突，主角在北境获得关键线索{i}。",
            "stateChanges": f"获得信物{i}" if i == 5 else "",
            "clueSignals": "青铜令暗纹异动" if i == 8 else "",
        }
        for i in range(1, 11)
    ]
    t1 = await distiller.summarize_rollup(
        tier_level=1,
        seq_no=1,
        start_chapter_no=1,
        end_chapter_no=10,
        items=sec_items,
        title="第一卷·北境初雪",
    )
    assert t1["summaryText"]
    assert "青铜令暗纹异动" in t1["openThreads"]
    assert len(t1["sourceHash"]) == 32

    t2 = await distiller.summarize_rollup(
        tier_level=2,
        seq_no=1,
        start_chapter_no=1,
        end_chapter_no=100,
        items=[{"label": "第1卷", "summaryText": t1["summaryText"], "openThreads": t1["openThreads"]}],
        title="第一篇·人界篇",
    )
    t3 = await distiller.summarize_rollup(
        tier_level=3,
        seq_no=1,
        start_chapter_no=1,
        end_chapter_no=1000,
        items=[{"label": "第一篇", "summaryText": t2["summaryText"], "openThreads": t2["openThreads"]}],
        title="第一纪元",
    )
    assert t2["summaryText"]
    assert t3["summaryText"]
    assert len(t3["summaryText"]) <= 300


@pytest.mark.asyncio
async def test_section_level_narrative_order_anti_spoiler_and_delete() -> None:
    settings = Settings(llm_api_key="")
    embedder = EmbeddingProvider(settings)
    store = InMemoryVectorStore()

    expr = build_multiscope_filter_expr(
        owner_id="owner-1",
        book_id="book-1",
        max_narrative_order=1002,
    )
    assert "narrative_order <= 1002" in expr

    texts = [
        "第一章第一节：林青在古井边捡到半枚青铜玉佩。",
        "第一章第二节：林青拿着青铜玉佩询问掌柜来历。",
        "第一章第三节（剧透）：青铜玉佩竟是魔尊封印钥匙，引发血洗全城。",
    ]
    vecs = await embedder.embed_texts(texts)
    await store.upsert_chunks(
        [
            VectorChunkRow(
                chunk_id="c1",
                book_id="book-1",
                owner_id="owner-1",
                document_id="doc-ch1",
                doc_version=1,
                parent_chunk_id="p1",
                chapter_no=1,
                doc_type="CHAPTER",
                scene_title="第一节",
                content=texts[0],
                dense_vec=vecs[0],
                narrative_order=1001,
                section_id="sec-1",
            ),
            VectorChunkRow(
                chunk_id="c2",
                book_id="book-1",
                owner_id="owner-1",
                document_id="doc-ch1",
                doc_version=1,
                parent_chunk_id="p2",
                chapter_no=1,
                doc_type="CHAPTER",
                scene_title="第二节",
                content=texts[1],
                dense_vec=vecs[1],
                narrative_order=1002,
                section_id="sec-2",
            ),
            VectorChunkRow(
                chunk_id="c3",
                book_id="book-1",
                owner_id="owner-1",
                document_id="doc-ch1",
                doc_version=1,
                parent_chunk_id="p3",
                chapter_no=1,
                doc_type="CHAPTER",
                scene_title="第三节",
                content=texts[2],
                dense_vec=vecs[2],
                narrative_order=1003,
                section_id="sec-3",
            ),
        ]
    )

    q_vec = (await embedder.embed_texts(["青铜玉佩 魔尊封印"]))[0]
    hits = await store.hybrid_search(
        owner_id="owner-1",
        book_id="book-1",
        query_text="青铜玉佩 魔尊封印",
        query_vector=q_vec,
        top_k=5,
        max_narrative_order=1002,
    )
    hit_ids = {h.chunk_id for h in hits}
    assert "c1" in hit_ids
    assert "c2" in hit_ids
    assert "c3" not in hit_ids  # Section 3 (1003) strictly blocked while writing Section 2 (1002)

    await store.delete_by_section(book_id="book-1", section_id="sec-2")
    hits_after = await store.hybrid_search(
        owner_id="owner-1",
        book_id="book-1",
        query_text="青铜玉佩",
        query_vector=q_vec,
        top_k=5,
        max_narrative_order=1002,
    )
    assert {h.chunk_id for h in hits_after} == {"c1"}


@pytest.mark.asyncio
async def test_epic_coarse_to_fine_1500_chapter_retrieval_and_l2_budget() -> None:
    settings = Settings(llm_api_key="")
    embedder = EmbeddingProvider(settings)
    reranker = RerankProvider(settings)
    chunk_store = InMemoryVectorStore()
    summary_store = InMemorySummaryVectorStore()

    # Ancient Ch.18 cold scene (order 18_001) + unrelated Ch.200 cold filler + Hot Ch.1480 scene + Future Ch.1600 spoiler
    docs = [
        ("ch18", 18, 18_001, "第十八章寒潭立誓：林青与白发老者签下寒霜星印契约，约定向北极星海归还玄冰匣。"),
        ("ch200", 200, 200_001, "第二百章坊市采购：普通丹药与灵石交易日常。"),
        ("ch1480", 1480, 1_480_001, "第一千四百八十章星海前夜：林青感应到体内的寒霜星印突然剧烈灼痛，玄冰匣就在眼前。"),
        ("ch1600", 1600, 1_600_001, "第一千六百章大结局剧透：寒霜星印彻底碎裂，老者真身显现。"),
    ]
    vecs = await embedder.embed_texts([d[3] for d in docs])
    await chunk_store.upsert_chunks(
        [
            VectorChunkRow(
                chunk_id=cid,
                book_id="epic-book",
                owner_id="author-1",
                document_id=f"doc-{cid}",
                doc_version=1,
                parent_chunk_id=f"p-{cid}",
                chapter_no=ch_no,
                doc_type="CHAPTER",
                scene_title=f"第{ch_no}章",
                content=txt,
                dense_vec=vec,
                narrative_order=order,
            )
            for (cid, ch_no, order, txt), vec in zip(docs, vecs, strict=True)
        ]
    )

    sum_texts = [
        ("sum-18", 18, 18_001, 18_001, "第十八章摘要：寒潭立誓，林青缔结寒霜星印契约并约定归还玄冰匣。"),
        ("sum-200", 200, 200_001, 200_001, "第二百章摘要：坊市采购丹药。"),
        ("sum-1600", 1600, 1_600_001, 1_600_001, "第一千六百章摘要：寒霜星印碎裂大结局剧透。"),
    ]
    sum_vecs = await embedder.embed_texts([s[4] for s in sum_texts])
    await summary_store.upsert_summaries(
        [
            SummaryVectorRow(
                node_id=nid,
                book_id="epic-book",
                owner_id="author-1",
                tier_level=0,
                start_order=s_ord,
                end_order=e_ord,
                chapter_no=ch_no,
                content=txt,
                dense_vec=vec,
            )
            for (nid, ch_no, s_ord, e_ord, txt), vec in zip(sum_texts, sum_vecs, strict=True)
        ]
    )

    retriever = HybridRagRetriever(
        settings=settings,
        vector_store=chunk_store,
        embedder=embedder,
        reranker=reranker,
        summary_store=summary_store,
    )
    # Bypass PG HTTP calls in unit test
    retriever._load_parents_from_pg = lambda *_args, **_kwargs: _async_none()  # type: ignore[method-assign]

    res = await retriever.search(
        owner_id="author-1",
        book_id="epic-book",
        query="寒霜星印 玄冰匣 契约",
        top_k=4,
        max_narrative_order=1_500_001,
        precision_mode="EPIC",
        user_doc_ids=[],
        assigned_doc_ids=[],
        global_doc_ids=[],
    )
    assert res["retrieval_strategy"] == "coarse_to_fine"
    assert any(r["node_id"] == "sum-18" for r in res["cold_summary_routes"])
    hit_chapters = {h["chapter_no"] for h in res["hits"]}
    assert 1480 in hit_chapters  # Hot zone direct hit
    assert 18 in hit_chapters  # Cold zone drilled down from summary_vectors
    assert 1600 not in hit_chapters  # Strictly blocked future spoiler

    # Verify L2 prompt assembly preserves full 3,200+ char reverse-budget rolling summary without 2,400-char truncation
    long_l2 = ("【前卷里程碑】" + "卷摘内容" * 800) + "\n【近期章摘】第1499章关键伏笔：玄冰匣开启倒计时！"
    assert 2700 < len(long_l2) < 4150
    msgs = build_novel_messages(
        action="CONTINUE",
        book_title="万章星海",
        genre="仙侠",
        synopsis="",
        chapter_title="第1500章",
        chapter_context="",
        instruction="续写",
        rolling_summary_context=long_l2,
    )
    assert "第1499章关键伏笔：玄冰匣开启倒计时！" in msgs[1]["content"]


async def _async_none() -> None:
    return None
