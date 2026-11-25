from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

import httpx

from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankCandidate, RerankProvider
from foliarc_agent.rag.vector_store import (
    SummaryVectorStore,
    VectorSearchHit,
    VectorStore,
)

SCOPE_WEIGHTS: dict[str, float] = {
    "BOOK": 1.25,
    "USER": 1.10,
    "ASSIGNED": 1.05,
    "GLOBAL": 1.00,
}

HOT_ZONE_ORDER_SPAN = 300_000
HOT_ZONE_BOOST = 1.15


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
    scope: str = "BOOK"
    narrative_order: int = 0

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
            "scope": self.scope,
            "narrative_order": self.narrative_order,
        }


class HybridRagRetriever:
    """
    Full Novel-Grade Hybrid RAG Pipeline (upgraded for 10,000-chapter Coarse-to-Fine routing):
    1. Optional Query Transformation (pronoun resolution + multi-query expansion)
    2. For <=300 units (PRECISE): single-stage Milvus Hybrid Search with section-level narrative_order anti-spoiler
       For >300 units (EPIC / AUTO): parallel Hot-Zone direct search (recent 300 units, x1.15 boost)
       + Cold-Zone Coarse-to-Fine (Stage 1 summary_vectors routing -> Stage 2 targeted order_ranges drill-down)
    3. Small-to-Big Parent Expansion via Java PG authoritative lookup
    4. Cross-Encoder Rerank + Multi-Scope precedence (`BOOK > USER > ASSIGNED > GLOBAL`)
    """

    def __init__(
        self,
        *,
        settings: Settings,
        vector_store: VectorStore,
        embedder: EmbeddingProvider,
        reranker: RerankProvider,
        summary_store: SummaryVectorStore | None = None,
    ) -> None:
        self._settings = settings
        self._store = vector_store
        self._embedder = embedder
        self._reranker = reranker
        self._summary_store = summary_store

    async def search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query: str,
        top_k: int = 5,
        max_chapter_no: int | None = None,
        max_narrative_order: int | None = None,
        precision_mode: str = "AUTO",
        doc_type: str | None = None,
        rewrite_query: bool = False,
        chapter_context: str = "",
        user_doc_ids: list[str] | None = None,
        assigned_doc_ids: list[str] | None = None,
        global_doc_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        # Resolve authorized USER / ASSIGNED / GLOBAL doc allowlists from biz-java if not pre-supplied
        if user_doc_ids is None and assigned_doc_ids is None and global_doc_ids is None:
            resolved = await self._load_accessible_docs(owner_id, book_id)
            user_doc_ids = resolved["user_doc_ids"]
            assigned_doc_ids = resolved["assigned_doc_ids"]
            global_doc_ids = resolved["global_doc_ids"]

        queries = [query.strip()]
        if rewrite_query and query.strip():
            rewritten = await self._transform_query(query.strip(), chapter_context)
            for q in rewritten:
                if q and q not in queries:
                    queries.append(q)

        effective_max_order = max_narrative_order
        if effective_max_order is None and max_chapter_no is not None and max_chapter_no > 0:
            effective_max_order = max_chapter_no * 1000 + 999

        mode_upper = (precision_mode or "AUTO").upper()
        use_coarse_to_fine = (
            self._summary_store is not None
            and effective_max_order is not None
            and effective_max_order > HOT_ZONE_ORDER_SPAN
            and mode_upper in ("AUTO", "EPIC")
        )

        vectors = await self._embedder.embed_texts(queries)
        fused_hits: dict[str, tuple[VectorSearchHit, float]] = {}
        cold_routes: list[dict[str, Any]] = []
        fetch_k = max(top_k * 3, 12)

        def _merge_hits(sub_hits: list[VectorSearchHit], boost: float = 1.0) -> None:
            for rank, hit in enumerate(sub_hits, start=1):
                key = hit.parent_chunk_id or hit.chunk_id
                add_score = (1.0 / (60.0 + rank)) * boost
                if key not in fused_hits:
                    fused_hits[key] = (hit, add_score)
                else:
                    prev_hit, prev_score = fused_hits[key]
                    best = hit if hit.score > prev_hit.score else prev_hit
                    fused_hits[key] = (best, prev_score + add_score)

        if use_coarse_to_fine and effective_max_order is not None and self._summary_store is not None:
            hot_cutoff = effective_max_order - HOT_ZONE_ORDER_SPAN
            primary_q = queries[0]
            primary_vec = vectors[0]

            async def _run_hot() -> list[list[VectorSearchHit]]:
                tasks = [
                    self._store.hybrid_search(
                        owner_id=owner_id,
                        book_id=book_id,
                        query_text=q_text,
                        query_vector=q_vec,
                        top_k=fetch_k,
                        max_narrative_order=effective_max_order,
                        min_narrative_order=hot_cutoff + 1,
                        doc_type=doc_type,
                        user_doc_ids=user_doc_ids,
                        assigned_doc_ids=assigned_doc_ids,
                        global_doc_ids=global_doc_ids,
                    )
                    for q_text, q_vec in zip(queries, vectors, strict=True)
                ]
                return list(await asyncio.gather(*tasks))

            async def _run_cold() -> tuple[list[dict[str, Any]], list[VectorSearchHit]]:
                summary_hits = await self._summary_store.search_summaries(
                    owner_id=owner_id,
                    book_id=book_id,
                    query_text=primary_q,
                    query_vector=primary_vec,
                    top_k=4,
                    max_order=hot_cutoff,
                )
                if not summary_hits:
                    return [], []
                ranges: list[tuple[int, int]] = []
                routes: list[dict[str, Any]] = []
                for s in summary_hits:
                    lo = max(1, s.start_order)
                    hi = min(hot_cutoff, max(s.end_order, lo))
                    ranges.append((lo, hi))
                    routes.append(
                        {
                            "node_id": s.node_id,
                            "tier_level": s.tier_level,
                            "start_order": lo,
                            "end_order": hi,
                            "score": s.score,
                        }
                    )
                drilled = await self._store.hybrid_search(
                    owner_id=owner_id,
                    book_id=book_id,
                    query_text=primary_q,
                    query_vector=primary_vec,
                    top_k=max(top_k, 6),
                    order_ranges=ranges,
                    doc_type=doc_type,
                )
                return routes, drilled

            hot_batches, (cold_routes, cold_hits) = await asyncio.gather(_run_hot(), _run_cold())
            for batch in hot_batches:
                _merge_hits(batch, boost=HOT_ZONE_BOOST)
            _merge_hits(cold_hits, boost=1.0)
        else:
            for q_text, q_vec in zip(queries, vectors, strict=True):
                sub_hits = await self._store.hybrid_search(
                    owner_id=owner_id,
                    book_id=book_id,
                    query_text=q_text,
                    query_vector=q_vec,
                    top_k=fetch_k,
                    max_chapter_no=max_chapter_no,
                    max_narrative_order=max_narrative_order,
                    doc_type=doc_type,
                    user_doc_ids=user_doc_ids,
                    assigned_doc_ids=assigned_doc_ids,
                    global_doc_ids=global_doc_ids,
                )
                _merge_hits(sub_hits, boost=1.0)

        strategy = "coarse_to_fine" if use_coarse_to_fine else "direct_hybrid"
        if not fused_hits:
            return {
                "query": query,
                "expanded_queries": queries,
                "max_chapter_no": max_chapter_no,
                "max_narrative_order": effective_max_order,
                "retrieval_strategy": strategy,
                "cold_summary_routes": cold_routes,
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

        ranked_ids = await self._reranker.rerank(query, candidates, top_k=max(top_k * 2, top_k))
        weighted_passages: list[RetrievedPassage] = []
        for item_id, base_rerank_score in ranked_ids:
            hit, parent_row = meta_by_key[item_id]
            effective_scope = str((parent_row or {}).get("scope") or hit.scope or "BOOK").upper()
            weight = SCOPE_WEIGHTS.get(effective_scope, 1.0)
            weighted_score = round(float(base_rerank_score) * weight, 5)
            weighted_passages.append(
                RetrievedPassage(
                    parent_chunk_id=hit.parent_chunk_id or hit.chunk_id,
                    document_id=hit.document_id,
                    document_title=(parent_row or {}).get("documentTitle") or hit.scene_title or "知识库文档",
                    doc_type=(parent_row or {}).get("docType") or hit.doc_type,
                    chapter_no=int((parent_row or {}).get("chapterNo", hit.chapter_no)),
                    scene_title=(parent_row or {}).get("sceneTitle") or hit.scene_title,
                    score=weighted_score,
                    matched_snippet=hit.content[:280],
                    parent_context=(parent_row or {}).get("content") or hit.content,
                    scope=effective_scope,
                    narrative_order=hit.narrative_order,
                )
            )

        weighted_passages.sort(key=lambda p: p.score, reverse=True)
        passages = [p.to_dict() for p in weighted_passages[:top_k]]

        return {
            "query": query,
            "expanded_queries": queries,
            "max_chapter_no": max_chapter_no,
            "max_narrative_order": effective_max_order,
            "retrieval_strategy": strategy,
            "cold_summary_routes": cold_routes,
            "embedding_mode": self._embedder.mode,
            "rerank_mode": self._reranker.mode,
            "hits": passages,
        }

    async def _load_accessible_docs(self, owner_id: str, book_id: str) -> dict[str, list[str]]:
        empty: dict[str, list[str]] = {"user_doc_ids": [], "assigned_doc_ids": [], "global_doc_ids": []}
        if not owner_id or not book_id:
            return empty
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/accessible-docs"
        headers = {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Owner-Id": owner_id,
        }
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code != 200:
                    return empty
                data = resp.json()
                return {
                    "user_doc_ids": [str(x) for x in (data.get("userDocIds") or [])],
                    "assigned_doc_ids": [str(x) for x in (data.get("assignedDocIds") or [])],
                    "global_doc_ids": [str(x) for x in (data.get("globalDocIds") or [])],
                }
        except (httpx.HTTPError, KeyError, ValueError):
            return empty

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
