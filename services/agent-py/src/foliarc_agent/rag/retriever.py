from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

import httpx

from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankCandidate, RerankProvider
from foliarc_agent.rag.vector_store import VectorSearchHit, VectorStore


@dataclass(frozen=True, slots=True)
class RetrievedPassage:
    parent_chunk_id: str
    document_id: str
    document_title: str
    doc_type: str
    chapter_no: int
    scene_title: str
    score: float
    matched_snippet: str
    parent_context: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "parent_chunk_id": self.parent_chunk_id,
            "document_id": self.document_id,
            "document_title": self.document_title,
            "doc_type": self.doc_type,
            "chapter_no": self.chapter_no,
            "scene_title": self.scene_title,
            "score": self.score,
            "matched_snippet": self.matched_snippet,
            "parent_context": self.parent_context,
        }


class HybridRagRetriever:
    """
    Full Novel-Grade Hybrid RAG Pipeline:
    1. Optional Query Transformation (pronoun resolution + multi-query expansion)
    2. Milvus Hybrid Search (Dense HNSW + Native BM25 + RRF Fusion + Anti-Spoiler Filter `chapter_no <= N`)
    3. Small-to-Big Parent Expansion via Java PG authoritative lookup (double-verifies non-deleted doc_version!)
    4. Cross-Encoder / Local Cross-Feature Rerank
    """

    def __init__(
        self,
        *,
        settings: Settings,
        vector_store: VectorStore,
        embedder: EmbeddingProvider,
        reranker: RerankProvider,
    ) -> None:
        self._settings = settings
        self._store = vector_store
        self._embedder = embedder
        self._reranker = reranker

    async def search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query: str,
        top_k: int = 5,
        max_chapter_no: int | None = None,
        doc_type: str | None = None,
        rewrite_query: bool = False,
        chapter_context: str = "",
    ) -> dict[str, Any]:
        queries = [query.strip()]
        if rewrite_query and query.strip():
            rewritten = await self._transform_query(query.strip(), chapter_context)
            for q in rewritten:
                if q and q not in queries:
                    queries.append(q)

        # Multi-query hybrid search + RRF fusion across expanded queries
        vectors = await self._embedder.embed_texts(queries)
        fused_hits: dict[str, tuple[VectorSearchHit, float]] = {}
        fetch_k = max(top_k * 3, 12)
        for q_text, q_vec in zip(queries, vectors, strict=True):
            sub_hits = await self._store.hybrid_search(
                owner_id=owner_id,
                book_id=book_id,
                query_text=q_text,
                query_vector=q_vec,
                top_k=fetch_k,
                max_chapter_no=max_chapter_no,
                doc_type=doc_type,
            )
            for rank, hit in enumerate(sub_hits, start=1):
                key = hit.parent_chunk_id or hit.chunk_id
                add_score = 1.0 / (60.0 + rank)
                if key not in fused_hits:
                    fused_hits[key] = (hit, add_score)
                else:
                    prev_hit, prev_score = fused_hits[key]
                    best = hit if hit.score > prev_hit.score else prev_hit
                    fused_hits[key] = (best, prev_score + add_score)

        if not fused_hits:
            return {
                "query": query,
                "expanded_queries": queries,
                "max_chapter_no": max_chapter_no,
                "embedding_mode": self._embedder.mode,
                "rerank_mode": self._reranker.mode,
                "hits": [],
            }

        # Small-to-Big: fetch authoritative Parent chunks from Java PG (filters out deleted/stale docs)
        parent_ids = [h.parent_chunk_id for h, _ in fused_hits.values() if h.parent_chunk_id]
        parent_map = await self._load_parents_from_pg(owner_id, book_id, parent_ids)

        candidates: list[RerankCandidate] = []
        meta_by_key: dict[str, tuple[VectorSearchHit, dict[str, Any] | None]] = {}
        for key, (hit, rrf_score) in fused_hits.items():
            parent_row = parent_map.get(hit.parent_chunk_id) if parent_map is not None else None
            # If PG parent lookup ran and document was deleted/superseded, drop stale Milvus hit!
            if parent_map is not None and hit.parent_chunk_id and parent_row is None:
                continue
            parent_text = (parent_row or {}).get("content") or hit.content
            doc_title = (parent_row or {}).get("documentTitle") or hit.scene_title or hit.document_id[:8]
            candidates.append(
                RerankCandidate(
                    item_id=key,
                    text=parent_text,
                    title=f"{doc_title} {hit.scene_title}",
                    base_score=rrf_score,
                )
            )
            meta_by_key[key] = (hit, parent_row)

        ranked_ids = await self._reranker.rerank(query, candidates, top_k=top_k)
        passages: list[dict[str, Any]] = []
        for item_id, final_score in ranked_ids:
            hit, parent_row = meta_by_key[item_id]
            passage = RetrievedPassage(
                parent_chunk_id=hit.parent_chunk_id or hit.chunk_id,
                document_id=hit.document_id,
                document_title=(parent_row or {}).get("documentTitle") or hit.scene_title or "知识库文档",
                doc_type=(parent_row or {}).get("docType") or hit.doc_type,
                chapter_no=int((parent_row or {}).get("chapterNo", hit.chapter_no)),
                scene_title=(parent_row or {}).get("sceneTitle") or hit.scene_title,
                score=final_score,
                matched_snippet=hit.content[:280],
                parent_context=(parent_row or {}).get("content") or hit.content,
            )
            passages.append(passage.to_dict())

        return {
            "query": query,
            "expanded_queries": queries,
            "max_chapter_no": max_chapter_no,
            "embedding_mode": self._embedder.mode,
            "rerank_mode": self._reranker.mode,
            "hits": passages,
        }

    async def _load_parents_from_pg(
        self,
        owner_id: str,
        book_id: str,
        parent_ids: list[str],
    ) -> dict[str, dict[str, Any]] | None:
        if not parent_ids:
            return None
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/parent-chunks"
        headers = {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Owner-Id": owner_id,
        }
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
                resp = await client.post(url, headers=headers, json=list(dict.fromkeys(parent_ids)))
                if resp.status_code != 200:
                    return None
                rows = resp.json()
                return {str(r["id"]): r for r in rows}
        except (httpx.HTTPError, KeyError, ValueError):
            return None

    async def _transform_query(self, query: str, chapter_context: str) -> list[str]:
        """Multi-Query expansion + pronoun disambiguation using fast LLM when configured."""
        if not self._settings.llm_configured:
            return []
        prompt = (
            "你是长篇小说检索查询改写器。请根据用户的查询与当前章节上下文，输出 2 条更精准的检索子查询"
            "（如果原句有“他/她/那把剑/那个宗门”等代词，请结合上下文消解成具体实体名词）。"
            '仅返回 JSON 数组，例如 ["改写查询1", "改写查询2"]。'
        )
        user_msg = f"当前上下文片段：\n{chapter_context[-600:]}\n\n原始查询：{query}"
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(12.0)) as client:
                resp = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {self._settings.llm_api_key}"},
                    json={
                        "model": self._settings.llm_model_fast_router,
                        "messages": [
                            {"role": "system", "content": prompt},
                            {"role": "user", "content": user_msg},
                        ],
                        "temperature": 0.1,
                        "max_tokens": 160,
                    },
                )
                if resp.status_code != 200:
                    return []
                raw = resp.json()["choices"][0]["message"]["content"].strip()
                start = raw.find("[")
                end = raw.rfind("]")
                if start >= 0 and end > start:
                    parsed = json.loads(raw[start : end + 1])
                    if isinstance(parsed, list):
                        return [str(x).strip() for x in parsed if str(x).strip()][:2]
        except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError):
            return []
        return []
