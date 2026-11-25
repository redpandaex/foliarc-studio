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
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider, local_hash_embed
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.summarizer import HierarchicalSummarizer
from foliarc_agent.rag.vector_store import (
    PARTITION_GLOBAL,
    PARTITION_USER,
    InMemoryVectorStore,
    VectorChunkRow,
    build_multiscope_filter_expr,
)
from foliarc_agent.streaming.llm_client import LlmStreamChunk


@pytest.mark.asyncio
async def test_multiscope_rag_precedence_isolation_and_anti_spoiler() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="", rerank_api_key="")
    store = InMemoryVectorStore()
    retriever = HybridRagRetriever(
        settings=settings,
        vector_store=store,
        embedder=EmbeddingProvider(settings),
        reranker=RerankProvider(settings),
    )

    book_rule = "筑基期寿元五百年，突破需服用星渊凝元丹。"
    global_rule = "筑基期寿元二百年，突破需服用普通筑基丹。"
    user_note = "筑基期斗法讲究气机牵引，不可一味硬拼。"
    other_user_secret = "其他作者私密设定：筑基期寿元一万年。"
    future_ch20 = "第20章剧透：主角筑基期陨落。"

    await store.upsert_chunks(
        [
            VectorChunkRow(
                chunk_id="c-global",
                book_id=PARTITION_GLOBAL,
                owner_id="admin-1",
                document_id="doc-global-1",
                doc_version=1,
                parent_chunk_id="p-global",
                chapter_no=0,
                doc_type="WORLDBOOK",
                scene_title="公共修仙辞典",
                content=global_rule,
                dense_vec=local_hash_embed(global_rule),
                scope="GLOBAL",
            ),
            VectorChunkRow(
                chunk_id="c-book",
                book_id="book-1",
                owner_id="author-1",
                document_id="doc-book-1",
                doc_version=1,
                parent_chunk_id="p-book",
                chapter_no=0,
                doc_type="WORLDBOOK",
                scene_title="本书境界规则",
                content=book_rule,
                dense_vec=local_hash_embed(book_rule),
                scope="BOOK",
            ),
            VectorChunkRow(
                chunk_id="c-user",
                book_id=PARTITION_USER,
                owner_id="author-1",
                document_id="doc-user-1",
                doc_version=1,
                parent_chunk_id="p-user",
                chapter_no=0,
                doc_type="STYLE_REF",
                scene_title="作者私库心得",
                content=user_note,
                dense_vec=local_hash_embed(user_note),
                scope="USER",
            ),
            VectorChunkRow(
                chunk_id="c-other-user",
                book_id=PARTITION_USER,
                owner_id="author-2",
                document_id="doc-user-1",  # even with colliding id, owner_id must isolate!
                doc_version=1,
                parent_chunk_id="p-other",
                chapter_no=0,
                doc_type="WORLDBOOK",
                scene_title="他人私库",
                content=other_user_secret,
                dense_vec=local_hash_embed(other_user_secret),
                scope="USER",
            ),
            VectorChunkRow(
                chunk_id="c-future",
                book_id="book-1",
                owner_id="author-1",
                document_id="doc-ch20",
                doc_version=1,
                parent_chunk_id="p-ch20",
                chapter_no=20,
                doc_type="CHAPTER",
                scene_title="第20章",
                content=future_ch20,
                dense_vec=local_hash_embed(future_ch20),
                scope="BOOK",
            ),
        ]
    )

    res = await retriever.search(
        owner_id="author-1",
        book_id="book-1",
        query="筑基期寿元与突破规则",
        top_k=5,
        max_chapter_no=5,
        user_doc_ids=["doc-user-1"],
        assigned_doc_ids=[],
        global_doc_ids=["doc-global-1"],
    )
    hits = res["hits"]
    doc_ids = [h["document_id"] for h in hits]
    assert "doc-book-1" in doc_ids
    assert "doc-user-1" in doc_ids
    assert "doc-global-1" in doc_ids
    # Anti-spoiler blocks Chapter 20 & tenant check blocks author-2
    assert "doc-ch20" not in doc_ids
    assert all("一万年" not in h["matched_snippet"] for h in hits)
    # BOOK precedence outranks GLOBAL even on nearly identical text
    assert hits[0]["document_id"] == "doc-book-1"
    assert hits[0]["scope"] == "BOOK"

    # Verify Milvus expression is strictly parenthesized for multi-tenant safety
    expr = build_multiscope_filter_expr(
        owner_id="author-1",
        book_id="book-1",
        max_chapter_no=5,
        doc_type="WORLDBOOK",
        user_doc_ids=["doc-user-1"],
        global_doc_ids=["doc-global-1"],
    )
    assert expr.startswith('(((book_id == "book-1" and owner_id == "author-1" and chapter_no <= 5) or ')
    assert 'and doc_type == "WORLDBOOK"' in expr


@pytest.mark.asyncio
async def test_hierarchical_summarizer_offline_fallbacks() -> None:
    summarizer = HierarchicalSummarizer(Settings(llm_api_key=""))
    ch = await summarizer.summarize_chapter(
        chapter_no=1,
        chapter_title="寒潭遗令",
        chapter_content="雨夜寒潭，林青与苏晚晴在断崖边发现一枚刻着星纹的残缺青铜令，背后杀机骤起。",
        known_characters=["林青", "苏晚晴", "路人甲"],
    )
    assert "青铜令" in ch["summaryText"]
    assert "林青" in ch["involvedCharacters"]
    assert "路人甲" not in ch["involvedCharacters"]

    vol_text, src_hash = await summarizer.summarize_volume(
        volume_no=1,
        start_chapter_no=1,
        end_chapter_no=10,
        chapter_summaries=[
            {"chapterNo": 1, "chapterTitle": "寒潭遗令", "summaryText": ch["summaryText"]},
        ],
    )
    assert "第1章" in vol_text
    assert len(src_hash) == 32

    charter = await summarizer.infer_story_charter(
        book_title="星渊剑录",
        genre="东方玄幻",
        existing_synopsis=None,
        chapter_summaries=[{"chapterNo": 1, "chapterTitle": "寒潭遗令", "summaryText": ch["summaryText"]}],
        known_characters=["林青", "苏晚晴"],
    )
    assert "星渊剑录" in charter
    assert "林青" in charter


class _ScriptedClient:
    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
        self.calls: list[list[dict[str, Any]]] = []

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        **kwargs: Any,
    ) -> AsyncIterator[LlmStreamChunk]:
        self.calls.append(messages)
        yield LlmStreamChunk(content_delta=self._responses.pop(0), input_tokens=50, cached_tokens=30, output_tokens=40)


@pytest.mark.asyncio
async def test_five_layer_context_flows_through_multi_agent_and_kv_prefix() -> None:
    fake = _ScriptedClient(
        [
            "1. 紧承第99章断桥异动\n2. 开启魔渊祭坛",
            "星光坠落断桥，林青反手握紧玄铁重剑踏入祭坛。",
            '{"passed": true, "score": 93, "issues": [], "revision_guidance": ""}',
        ]
    )
    orchestrator = EditorialTeamOrchestrator(
        settings=Settings(llm_api_key=""),
        llm_client=FallbackStreamClient(fake),  # type: ignore[arg-type]
        router=IntentAndModelRouter(settings=Settings(llm_api_key="")),
        checkpoint_store=InMemoryCheckpointStore(),
        skill_registry=SkillRegistry(),
        rag_retriever=None,
        enable_mcp=False,
    )

    async def emit(_t: str, _p: dict[str, Any], _a: str = "writer") -> str:
        return "ev-1"

    async def check_ctrl() -> None:
        return None

    board = EditorialBlackboard(
        run_id="run-5layer",
        book_title="星渊百章录",
        chapter_no=100,
        chapter_title="决战星渊",
        story_charter="L0故事宪章：冷峻写实第三人称，主角林青持玄铁重剑。",
        rolling_summary_context="【前卷主线里程碑】第1卷得青铜令；【近期章节摘要】第95章北境兵变。",
        active_state_brief="【当前活跃人物状态】林青（元婴初期，魔渊断桥）。",
        previous_chapters_context="【上一章（第99章）原文衔接】青铜令骤然发烫。",
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
    assert len(fake.calls) == 3

    # Verify WRITER places L0 Story Charter inside System Prompt (index 0) for KV Cache prefix!
    writer_sys = fake.calls[1][0]["content"]
    writer_user = fake.calls[1][1]["content"]
    assert "L0故事宪章" in writer_sys
    assert "冲突优先级" in writer_sys
    assert "第95章北境兵变" in writer_user
    assert "元婴初期" in writer_user
    assert "青铜令骤然发烫" in writer_user
