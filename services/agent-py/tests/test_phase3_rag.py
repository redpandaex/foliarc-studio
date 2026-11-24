import io

import pymupdf
import pytest
from docx import Document as DocxDocument

from foliarc_agent.config import Settings
from foliarc_agent.rag.chunker import build_parent_child_chunks
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider, local_hash_embed
from foliarc_agent.rag.memory_extractor import StructuredMemoryExtractor
from foliarc_agent.rag.parsers import parse_document_bytes
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.vector_store import InMemoryVectorStore, VectorChunkRow


def test_multiformat_parsers_pdf_docx_markdown() -> None:
    # 1. Markdown with headings & scene breaks
    md = "# 青云宗宗规\n\n第一条：外门弟子不得擅闯寒潭禁地。\n\n---\n\n玄铁重剑藏于后山剑冢。"
    md_sections = parse_document_bytes("lore.md", md.encode("utf-8"))
    assert len(md_sections) == 2
    assert md_sections[0].title == "青云宗宗规"
    assert "寒潭禁地" in md_sections[0].content

    # 2. Word (.docx)
    docx = DocxDocument()
    docx.add_heading("第一卷 炼气篇", level=1)
    docx.add_paragraph("林青在落日峡谷拾得残缺青铜令。")
    buf = io.BytesIO()
    docx.save(buf)
    docx_sections = parse_document_bytes("setting.docx", buf.getvalue())
    assert len(docx_sections) == 1
    assert "第一卷" in docx_sections[0].title
    assert "青铜令" in docx_sections[0].content

    # 3. PDF via PyMuPDF
    pdf = pymupdf.open()
    page = pdf.new_page()
    page.insert_text((72, 72), "Ancient Sect Compendium: Starfall Blade Rules.")
    pdf_bytes = pdf.tobytes()
    pdf.close()
    pdf_sections = parse_document_bytes("guide.pdf", pdf_bytes)
    assert len(pdf_sections) == 1
    assert "Starfall Blade" in pdf_sections[0].content


def test_novel_aware_parent_child_chunker_preserves_dialogue() -> None:
    dialogue = (
        "林青按住腰间断剑，冷声道：“这枚青铜令是我师尊临终所托。"
        "你若今日强夺，不仅违背青云宗三百年宗规，更会引动后山剑冢杀阵！难道你想让长老堂全员陪葬吗？”"
    )
    filler = "山风呼啸，松涛翻涌，四周十余名黑衣刺客齐齐后退半步，目光惊疑不定。" * 8
    md = f"# 断崖对峙\n\n{filler}\n\n{dialogue}\n\n{filler}"
    sections = parse_document_bytes("ch3.md", md.encode("utf-8"))

    parents, children = build_parent_child_chunks(
        sections,
        chapter_no=3,
        doc_type="CHAPTER",
        doc_title="第3章 断崖对峙",
        parent_max_chars=900,
        child_max_chars=240,
        child_overlap_chars=40,
    )
    assert len(parents) >= 1
    assert len(children) >= 3
    parent_ids = {p.id for p in parents}
    assert all(c.parent_chunk_id in parent_ids for c in children)

    # Verify the quoted dialogue stayed intact inside at least one child chunk
    quoted_segment = "这枚青铜令是我师尊临终所托。你若今日强夺，不仅违背青云宗三百年宗规，更会引动后山剑冢杀阵！难道你想让长老堂全员陪葬吗？"
    assert any(quoted_segment in c.content for c in children)


@pytest.mark.asyncio
async def test_hybrid_rag_anti_spoiler_tenant_isolation_and_dual_write_delete() -> None:
    settings = Settings(llm_api_key="", embedding_api_key="", rerank_api_key="")
    store = InMemoryVectorStore()
    embedder = EmbeddingProvider(settings)
    reranker = RerankProvider(settings)
    retriever = HybridRagRetriever(
        settings=settings,
        vector_store=store,
        embedder=embedder,
        reranker=reranker,
    )

    rows = [
        VectorChunkRow(
            chunk_id="c-world",
            book_id="book-1",
            owner_id="owner-1",
            document_id="doc-world",
            doc_version=1,
            parent_chunk_id="p-world",
            chapter_no=0,
            doc_type="WORLDBOOK",
            scene_title="兵器谱",
            content="玄铁重剑重达八十一斤，剑脊刻有星辰阵纹，唯筑基期以上方可催动。",
            dense_vec=local_hash_embed("玄铁重剑重达八十一斤，剑脊刻有星辰阵纹，唯筑基期以上方可催动。"),
        ),
        VectorChunkRow(
            chunk_id="c-ch10",
            book_id="book-1",
            owner_id="owner-1",
            document_id="doc-ch10",
            doc_version=1,
            parent_chunk_id="p-ch10",
            chapter_no=10,
            doc_type="CHAPTER",
            scene_title="第10章 终局伏笔",
            content="第10章剧透：玄铁重剑在魔渊崩碎，林青因此跌落境界。",
            dense_vec=local_hash_embed("第10章剧透：玄铁重剑在魔渊崩碎，林青因此跌落境界。"),
        ),
        VectorChunkRow(
            chunk_id="c-other-tenant",
            book_id="book-1",
            owner_id="owner-2",
            document_id="doc-other",
            doc_version=1,
            parent_chunk_id="p-other",
            chapter_no=0,
            doc_type="WORLDBOOK",
            scene_title="他人设定",
            content="租户2的绝密玄铁重剑设定。",
            dense_vec=local_hash_embed("租户2的绝密玄铁重剑设定。"),
        ),
    ]
    await store.upsert_chunks(rows)

    # Anti-spoiler check: writing chapter 3 must NOT retrieve chapter 10 or owner-2!
    res = await retriever.search(
        owner_id="owner-1",
        book_id="book-1",
        query="玄铁重剑有什么特性",
        top_k=5,
        max_chapter_no=3,
    )
    assert res["embedding_mode"] == "local_fallback"
    assert len(res["hits"]) == 1
    assert res["hits"][0]["document_id"] == "doc-world"
    assert "八十一斤" in res["hits"][0]["matched_snippet"]

    # Dual-write delete consistency: deleting doc-world must immediately remove it from search
    await store.delete_by_document(book_id="book-1", document_id="doc-world", max_version=2)
    res_after_delete = await retriever.search(
        owner_id="owner-1",
        book_id="book-1",
        query="玄铁重剑",
        top_k=5,
        max_chapter_no=3,
    )
    assert res_after_delete["hits"] == []


@pytest.mark.asyncio
async def test_memory_extractor_fallback_extracts_timeline_and_known_characters() -> None:
    settings = Settings(llm_api_key="")
    extractor = StructuredMemoryExtractor(settings)
    result = await extractor.extract(
        chapter_no=4,
        chapter_title="寒潭悟剑",
        chapter_content="林青在寒潭边偶遇苏晚晴，二人联手击退黑袍长老。",
        existing_bible={"characters": [{"name": "林青"}, {"name": "苏晚晴"}, {"name": "路人甲"}]},
    )
    assert result["chapterNo"] == 4
    assert len(result["timelineEvents"]) == 1
    assert "林青" in result["timelineEvents"][0]["involvedCharacters"]
    assert "苏晚晴" in result["timelineEvents"][0]["involvedCharacters"]
    assert "路人甲" not in result["timelineEvents"][0]["involvedCharacters"]
