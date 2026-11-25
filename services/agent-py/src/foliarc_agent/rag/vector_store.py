from __future__ import annotations

import asyncio
import math
import re
from dataclasses import dataclass
from typing import Any, Protocol

from pymilvus import AnnSearchRequest, DataType, Function, FunctionType, MilvusClient, RRFRanker
from pymilvus.exceptions import MilvusException

from foliarc_agent.config import Settings

PARTITION_USER = "__USER__"
PARTITION_GLOBAL = "__GLOBAL__"


def derive_narrative_order(chapter_no: int, narrative_order: int = 0) -> int:
    if narrative_order > 0:
        return int(narrative_order)
    if chapter_no > 0:
        return int(chapter_no) * 1000 + 1
    return 0


@dataclass(frozen=True, slots=True)
class VectorChunkRow:
    chunk_id: str
    book_id: str
    owner_id: str
    document_id: str
    doc_version: int
    parent_chunk_id: str
    chapter_no: int
    doc_type: str
    scene_title: str
    content: str
    dense_vec: list[float]
    scope: str = "BOOK"
    narrative_order: int = 0
    section_id: str = ""

    @property
    def effective_order(self) -> int:
        return derive_narrative_order(self.chapter_no, self.narrative_order)


@dataclass(frozen=True, slots=True)
class VectorSearchHit:
    chunk_id: str
    parent_chunk_id: str
    document_id: str
    doc_version: int
    chapter_no: int
    doc_type: str
    scene_title: str
    content: str
    score: float
    scope: str = "BOOK"
    narrative_order: int = 0
    section_id: str = ""


@dataclass(frozen=True, slots=True)
class SummaryVectorRow:
    node_id: str
    book_id: str
    owner_id: str
    tier_level: int
    start_order: int
    end_order: int
    chapter_no: int
    content: str
    dense_vec: list[float]


@dataclass(frozen=True, slots=True)
class SummarySearchHit:
    node_id: str
    tier_level: int
    start_order: int
    end_order: int
    chapter_no: int
    content: str
    score: float


class VectorStore(Protocol):
    """Abstract vector storage protocol decoupling Hybrid RAG from concrete engines."""

    async def ensure_collection(self) -> None: ...

    async def upsert_chunks(self, rows: list[VectorChunkRow]) -> int: ...

    async def delete_stale_versions(self, *, book_id: str, document_id: str, keep_version: int) -> None: ...

    async def delete_by_document(self, *, book_id: str, document_id: str, max_version: int | None = None) -> None: ...

    async def delete_by_section(self, *, book_id: str, section_id: str) -> None: ...

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        max_narrative_order: int | None = None,
        min_narrative_order: int | None = None,
        order_ranges: list[tuple[int, int]] | None = None,
        doc_type: str | None = None,
        user_doc_ids: list[str] | None = None,
        assigned_doc_ids: list[str] | None = None,
        global_doc_ids: list[str] | None = None,
    ) -> list[VectorSearchHit]: ...

    async def health_check(self) -> bool: ...


class SummaryVectorStore(Protocol):
    """Lightweight summary-routing index (`foliarc_summary_vectors`) for Coarse-to-Fine retrieval."""

    async def ensure_collection(self) -> None: ...

    async def upsert_summaries(self, rows: list[SummaryVectorRow]) -> int: ...

    async def delete_by_node(self, *, book_id: str, node_id: str) -> None: ...

    async def search_summaries(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 4,
        max_order: int | None = None,
    ) -> list[SummarySearchHit]: ...


def _escape_expr(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _build_in_list(ids: list[str]) -> str:
    return "[" + ", ".join(f'"{_escape_expr(x)}"' for x in ids) + "]"


def build_multiscope_filter_expr(
    *,
    owner_id: str,
    book_id: str,
    max_chapter_no: int | None = None,
    max_narrative_order: int | None = None,
    min_narrative_order: int | None = None,
    order_ranges: list[tuple[int, int]] | None = None,
    doc_type: str | None = None,
    user_doc_ids: list[str] | None = None,
    assigned_doc_ids: list[str] | None = None,
    global_doc_ids: list[str] | None = None,
) -> str:
    """
    Builds a strictly parenthesized Milvus scalar pre-filter across:
    1. BOOK scope: book_id + owner_id + section-level anti-spoiler (narrative_order / chapter_no)
       plus optional hot-zone window or Coarse-to-Fine cold order_ranges
    2. USER scope: __USER__ partition + owner_id + allowed user doc IDs (no anti-spoiler cutoff)
    3. ASSIGNED & GLOBAL scope: __GLOBAL__ partition + Java-authorized doc IDs
    """
    book_clauses = [
        f'book_id == "{_escape_expr(book_id)}"',
        f'owner_id == "{_escape_expr(owner_id)}"',
    ]
    if order_ranges:
        range_parts = [
            f"(narrative_order >= {int(lo)} and narrative_order <= {int(hi)})"
            for lo, hi in order_ranges
            if hi >= lo
        ]
        if range_parts:
            book_clauses.append("(" + " or ".join(range_parts) + ")")
    elif max_narrative_order is not None and max_narrative_order >= 0:
        book_clauses.append(f"narrative_order <= {int(max_narrative_order)}")
        if min_narrative_order is not None and min_narrative_order > 0:
            book_clauses.append(f"(narrative_order >= {int(min_narrative_order)} or narrative_order == 0)")
    elif max_chapter_no is not None and max_chapter_no >= 0:
        book_clauses.append(f"chapter_no <= {int(max_chapter_no)}")

    branches = ["(" + " and ".join(book_clauses) + ")"]

    clean_user = [x for x in (user_doc_ids or []) if x]
    if clean_user:
        branches.append(
            f'(book_id == "{PARTITION_USER}" and owner_id == "{_escape_expr(owner_id)}" '
            f"and document_id in {_build_in_list(clean_user)})"
        )

    clean_platform = list(dict.fromkeys([x for x in (assigned_doc_ids or []) + (global_doc_ids or []) if x]))
    if clean_platform:
        branches.append(
            f'(book_id == "{PARTITION_GLOBAL}" and document_id in {_build_in_list(clean_platform)})'
        )

    scope_expr = branches[0] if len(branches) == 1 else "(" + " or ".join(branches) + ")"
    if doc_type:
        return f'({scope_expr}) and doc_type == "{_escape_expr(doc_type.upper())}"'
    return scope_expr


def _classify_scope(
    document_id: str,
    user_set: set[str],
    assigned_set: set[str],
    global_set: set[str],
) -> str:
    if document_id in assigned_set:
        return "ASSIGNED"
    if document_id in user_set:
        return "USER"
    if document_id in global_set:
        return "GLOBAL"
    return "BOOK"


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (na * nb)


class MilvusVectorStore:
    """
    Enterprise Milvus Standalone VectorStore (`foliarc_novel_chunks_v2`):
    - Partition Key multi-tenancy on `book_id`
    - Dense HNSW (COSINE) + Native BM25 Sparse Inverted Index
    - Native `RRFRanker(k=60)` hybrid search fusion
    - Section-level monotonic `narrative_order` & `section_id` scalar indexes for anti-spoiler + Coarse-to-Fine
    """

    def __init__(self, settings: Settings) -> None:
        self._uri = settings.milvus_uri
        self._collection = settings.milvus_collection
        self._dim = settings.embedding_dim
        self._client: MilvusClient | None = None
        self._ensured = False

    def _get_client(self) -> MilvusClient:
        if self._client is None:
            self._client = MilvusClient(uri=self._uri, timeout=15.0)
        return self._client

    def _ensure_sync(self) -> None:
        if self._ensured:
            return
        client = self._get_client()
        if client.has_collection(self._collection):
            client.load_collection(self._collection)
            self._ensured = True
            return

        schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field(field_name="chunk_id", datatype=DataType.VARCHAR, is_primary=True, max_length=64)
        schema.add_field(
            field_name="book_id",
            datatype=DataType.VARCHAR,
            max_length=64,
            is_partition_key=True,
        )
        schema.add_field(field_name="owner_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="document_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="doc_version", datatype=DataType.INT64)
        schema.add_field(field_name="parent_chunk_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="chapter_no", datatype=DataType.INT64)
        schema.add_field(field_name="narrative_order", datatype=DataType.INT64)
        schema.add_field(field_name="section_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="doc_type", datatype=DataType.VARCHAR, max_length=32)
        schema.add_field(field_name="scene_title", datatype=DataType.VARCHAR, max_length=256)
        schema.add_field(
            field_name="content",
            datatype=DataType.VARCHAR,
            max_length=8192,
            enable_analyzer=True,
            analyzer_params={"type": "chinese"},
        )
        schema.add_field(field_name="dense_vec", datatype=DataType.FLOAT_VECTOR, dim=self._dim)
        schema.add_field(field_name="sparse_vec", datatype=DataType.SPARSE_FLOAT_VECTOR)

        bm25_fn = Function(
            name="content_bm25",
            function_type=FunctionType.BM25,
            input_field_names=["content"],
            output_field_names=["sparse_vec"],
        )
        schema.add_function(bm25_fn)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="dense_vec",
            index_type="HNSW",
            metric_type="COSINE",
            params={"M": 16, "efConstruction": 128},
        )
        index_params.add_index(
            field_name="sparse_vec",
            index_type="SPARSE_INVERTED_INDEX",
            metric_type="BM25",
        )
        index_params.add_index(field_name="document_id", index_type="INVERTED")
        index_params.add_index(field_name="chapter_no", index_type="INVERTED")
        index_params.add_index(field_name="narrative_order", index_type="INVERTED")
        index_params.add_index(field_name="section_id", index_type="INVERTED")
        index_params.add_index(field_name="doc_type", index_type="INVERTED")

        client.create_collection(
            collection_name=self._collection,
            schema=schema,
            index_params=index_params,
            num_partitions=16,
            consistency_level="Strong",
        )
        client.load_collection(self._collection)
        self._ensured = True

    async def ensure_collection(self) -> None:
        await asyncio.to_thread(self._ensure_sync)

    async def upsert_chunks(self, rows: list[VectorChunkRow]) -> int:
        if not rows:
            return 0
        await self.ensure_collection()

        def _do() -> int:
            client = self._get_client()
            payload = [
                {
                    "chunk_id": r.chunk_id,
                    "book_id": r.book_id,
                    "owner_id": r.owner_id,
                    "document_id": r.document_id,
                    "doc_version": int(r.doc_version),
                    "parent_chunk_id": r.parent_chunk_id,
                    "chapter_no": int(r.chapter_no),
                    "narrative_order": r.effective_order,
                    "section_id": (r.section_id or "")[:64],
                    "doc_type": r.doc_type[:32],
                    "scene_title": r.scene_title[:250],
                    "content": r.content[:8000],
                    "dense_vec": r.dense_vec,
                }
                for r in rows
            ]
            client.upsert(collection_name=self._collection, data=payload)
            return len(payload)

        return await asyncio.to_thread(_do)

    async def delete_stale_versions(self, *, book_id: str, document_id: str, keep_version: int) -> None:
        await self.ensure_collection()
        expr = (
            f'book_id == "{_escape_expr(book_id)}" '
            f'and document_id == "{_escape_expr(document_id)}" '
            f"and doc_version < {int(keep_version)}"
        )
        await asyncio.to_thread(
            lambda: self._get_client().delete(collection_name=self._collection, filter=expr)
        )

    async def delete_by_document(
        self,
        *,
        book_id: str,
        document_id: str,
        max_version: int | None = None,
    ) -> None:
        await self.ensure_collection()
        expr = (
            f'book_id == "{_escape_expr(book_id)}" '
            f'and document_id == "{_escape_expr(document_id)}"'
        )
        if max_version is not None:
            expr += f" and doc_version <= {int(max_version)}"
        await asyncio.to_thread(
            lambda: self._get_client().delete(collection_name=self._collection, filter=expr)
        )

    async def delete_by_section(self, *, book_id: str, section_id: str) -> None:
        if not section_id:
            return
        await self.ensure_collection()
        expr = (
            f'book_id == "{_escape_expr(book_id)}" '
            f'and section_id == "{_escape_expr(section_id)}"'
        )
        await asyncio.to_thread(
            lambda: self._get_client().delete(collection_name=self._collection, filter=expr)
        )

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        max_narrative_order: int | None = None,
        min_narrative_order: int | None = None,
        order_ranges: list[tuple[int, int]] | None = None,
        doc_type: str | None = None,
        user_doc_ids: list[str] | None = None,
        assigned_doc_ids: list[str] | None = None,
        global_doc_ids: list[str] | None = None,
    ) -> list[VectorSearchHit]:
        await self.ensure_collection()
        expr = build_multiscope_filter_expr(
            owner_id=owner_id,
            book_id=book_id,
            max_chapter_no=max_chapter_no,
            max_narrative_order=max_narrative_order,
            min_narrative_order=min_narrative_order,
            order_ranges=order_ranges,
            doc_type=doc_type,
            user_doc_ids=user_doc_ids,
            assigned_doc_ids=assigned_doc_ids,
            global_doc_ids=global_doc_ids,
        )
        user_set = set(user_doc_ids or [])
        assigned_set = set(assigned_doc_ids or [])
        global_set = set(global_doc_ids or [])

        def _do() -> list[VectorSearchHit]:
            client = self._get_client()
            candidate_k = max(top_k * 2, 12)
            dense_req = AnnSearchRequest(
                data=[query_vector],
                anns_field="dense_vec",
                param={"metric_type": "COSINE", "params": {"ef": 64}},
                limit=candidate_k,
                expr=expr,
            )
            sparse_req = AnnSearchRequest(
                data=[query_text],
                anns_field="sparse_vec",
                param={"metric_type": "BM25", "params": {"drop_ratio_search": 0.1}},
                limit=candidate_k,
                expr=expr,
            )
            results = client.hybrid_search(
                collection_name=self._collection,
                reqs=[dense_req, sparse_req],
                ranker=RRFRanker(k=60),
                limit=top_k,
                output_fields=[
                    "chunk_id",
                    "parent_chunk_id",
                    "document_id",
                    "doc_version",
                    "chapter_no",
                    "narrative_order",
                    "section_id",
                    "doc_type",
                    "scene_title",
                    "content",
                ],
            )
            hits: list[VectorSearchHit] = []
            if not results:
                return hits
            for item in results[0]:
                entity: dict[str, Any] = item.get("entity", item)
                score = float(item.get("distance", item.get("score", 0.0)))
                doc_id = str(entity.get("document_id", ""))
                ch_no = int(entity.get("chapter_no", 0))
                order = int(entity.get("narrative_order", 0) or derive_narrative_order(ch_no))
                hits.append(
                    VectorSearchHit(
                        chunk_id=str(entity.get("chunk_id", item.get("id", ""))),
                        parent_chunk_id=str(entity.get("parent_chunk_id", "")),
                        document_id=doc_id,
                        doc_version=int(entity.get("doc_version", 1)),
                        chapter_no=ch_no,
                        doc_type=str(entity.get("doc_type", "WORLDBOOK")),
                        scene_title=str(entity.get("scene_title", "")),
                        content=str(entity.get("content", "")),
                        score=score,
                        scope=_classify_scope(doc_id, user_set, assigned_set, global_set),
                        narrative_order=order,
                        section_id=str(entity.get("section_id", "")),
                    )
                )
            return hits

        return await asyncio.to_thread(_do)

    async def health_check(self) -> bool:
        try:
            await asyncio.to_thread(lambda: self._get_client().list_collections())
            return True
        except (MilvusException, OSError, RuntimeError):
            return False


class MilvusSummaryVectorStore:
    """Milvus collection `foliarc_summary_vectors` for Coarse-to-Fine 10,000-chapter cold routing."""

    def __init__(self, settings: Settings) -> None:
        self._uri = settings.milvus_uri
        self._collection = settings.milvus_summary_collection
        self._dim = settings.embedding_dim
        self._client: MilvusClient | None = None
        self._ensured = False

    def _get_client(self) -> MilvusClient:
        if self._client is None:
            self._client = MilvusClient(uri=self._uri, timeout=15.0)
        return self._client

    def _ensure_sync(self) -> None:
        if self._ensured:
            return
        client = self._get_client()
        if client.has_collection(self._collection):
            client.load_collection(self._collection)
            self._ensured = True
            return

        schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field(field_name="node_id", datatype=DataType.VARCHAR, is_primary=True, max_length=64)
        schema.add_field(field_name="book_id", datatype=DataType.VARCHAR, max_length=64, is_partition_key=True)
        schema.add_field(field_name="owner_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="tier_level", datatype=DataType.INT64)
        schema.add_field(field_name="start_order", datatype=DataType.INT64)
        schema.add_field(field_name="end_order", datatype=DataType.INT64)
        schema.add_field(field_name="chapter_no", datatype=DataType.INT64)
        schema.add_field(
            field_name="content",
            datatype=DataType.VARCHAR,
            max_length=4096,
            enable_analyzer=True,
            analyzer_params={"type": "chinese"},
        )
        schema.add_field(field_name="dense_vec", datatype=DataType.FLOAT_VECTOR, dim=self._dim)
        schema.add_field(field_name="sparse_vec", datatype=DataType.SPARSE_FLOAT_VECTOR)
        schema.add_function(
            Function(
                name="summary_content_bm25",
                function_type=FunctionType.BM25,
                input_field_names=["content"],
                output_field_names=["sparse_vec"],
            )
        )

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="dense_vec",
            index_type="HNSW",
            metric_type="COSINE",
            params={"M": 16, "efConstruction": 128},
        )
        index_params.add_index(
            field_name="sparse_vec",
            index_type="SPARSE_INVERTED_INDEX",
            metric_type="BM25",
        )
        index_params.add_index(field_name="start_order", index_type="INVERTED")
        index_params.add_index(field_name="end_order", index_type="INVERTED")
        index_params.add_index(field_name="tier_level", index_type="INVERTED")

        client.create_collection(
            collection_name=self._collection,
            schema=schema,
            index_params=index_params,
            num_partitions=16,
            consistency_level="Strong",
        )
        client.load_collection(self._collection)
        self._ensured = True

    async def ensure_collection(self) -> None:
        await asyncio.to_thread(self._ensure_sync)

    async def upsert_summaries(self, rows: list[SummaryVectorRow]) -> int:
        if not rows:
            return 0
        await self.ensure_collection()

        def _do() -> int:
            payload = [
                {
                    "node_id": r.node_id[:64],
                    "book_id": r.book_id[:64],
                    "owner_id": r.owner_id[:64],
                    "tier_level": int(r.tier_level),
                    "start_order": int(r.start_order),
                    "end_order": int(r.end_order),
                    "chapter_no": int(r.chapter_no),
                    "content": r.content[:4000],
                    "dense_vec": r.dense_vec,
                }
                for r in rows
            ]
            self._get_client().upsert(collection_name=self._collection, data=payload)
            return len(payload)

        return await asyncio.to_thread(_do)

    async def delete_by_node(self, *, book_id: str, node_id: str) -> None:
        await self.ensure_collection()
        expr = f'book_id == "{_escape_expr(book_id)}" and node_id == "{_escape_expr(node_id)}"'
        await asyncio.to_thread(
            lambda: self._get_client().delete(collection_name=self._collection, filter=expr)
        )

    async def search_summaries(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 4,
        max_order: int | None = None,
    ) -> list[SummarySearchHit]:
        await self.ensure_collection()
        clauses = [
            f'book_id == "{_escape_expr(book_id)}"',
            f'owner_id == "{_escape_expr(owner_id)}"',
        ]
        if max_order is not None and max_order >= 0:
            clauses.append(f"end_order <= {int(max_order)}")
        expr = " and ".join(clauses)

        def _do() -> list[SummarySearchHit]:
            client = self._get_client()
            candidate_k = max(top_k * 2, 8)
            dense_req = AnnSearchRequest(
                data=[query_vector],
                anns_field="dense_vec",
                param={"metric_type": "COSINE", "params": {"ef": 64}},
                limit=candidate_k,
                expr=expr,
            )
            sparse_req = AnnSearchRequest(
                data=[query_text],
                anns_field="sparse_vec",
                param={"metric_type": "BM25", "params": {"drop_ratio_search": 0.1}},
                limit=candidate_k,
                expr=expr,
            )
            results = client.hybrid_search(
                collection_name=self._collection,
                reqs=[dense_req, sparse_req],
                ranker=RRFRanker(k=60),
                limit=top_k,
                output_fields=["node_id", "tier_level", "start_order", "end_order", "chapter_no", "content"],
            )
            hits: list[SummarySearchHit] = []
            if not results:
                return hits
            for item in results[0]:
                entity: dict[str, Any] = item.get("entity", item)
                hits.append(
                    SummarySearchHit(
                        node_id=str(entity.get("node_id", item.get("id", ""))),
                        tier_level=int(entity.get("tier_level", 0)),
                        start_order=int(entity.get("start_order", 0)),
                        end_order=int(entity.get("end_order", 0)),
                        chapter_no=int(entity.get("chapter_no", 0)),
                        content=str(entity.get("content", "")),
                        score=float(item.get("distance", item.get("score", 0.0))),
                    )
                )
            return hits

        return await asyncio.to_thread(_do)


class InMemoryVectorStore:
    """In-memory implementation of VectorStore for fast deterministic unit tests."""

    def __init__(self) -> None:
        self._rows: dict[str, VectorChunkRow] = {}

    async def ensure_collection(self) -> None:
        return None

    async def upsert_chunks(self, rows: list[VectorChunkRow]) -> int:
        for r in rows:
            self._rows[r.chunk_id] = r
        return len(rows)

    async def delete_stale_versions(self, *, book_id: str, document_id: str, keep_version: int) -> None:
        to_remove = [
            cid
            for cid, r in self._rows.items()
            if r.book_id == book_id and r.document_id == document_id and r.doc_version < keep_version
        ]
        for cid in to_remove:
            self._rows.pop(cid, None)

    async def delete_by_document(
        self,
        *,
        book_id: str,
        document_id: str,
        max_version: int | None = None,
    ) -> None:
        to_remove = [
            cid
            for cid, r in self._rows.items()
            if r.book_id == book_id
            and r.document_id == document_id
            and (max_version is None or r.doc_version <= max_version)
        ]
        for cid in to_remove:
            self._rows.pop(cid, None)

    async def delete_by_section(self, *, book_id: str, section_id: str) -> None:
        if not section_id:
            return
        to_remove = [
            cid
            for cid, r in self._rows.items()
            if r.book_id == book_id and r.section_id == section_id
        ]
        for cid in to_remove:
            self._rows.pop(cid, None)

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        max_narrative_order: int | None = None,
        min_narrative_order: int | None = None,
        order_ranges: list[tuple[int, int]] | None = None,
        doc_type: str | None = None,
        user_doc_ids: list[str] | None = None,
        assigned_doc_ids: list[str] | None = None,
        global_doc_ids: list[str] | None = None,
    ) -> list[VectorSearchHit]:
        user_set = set(user_doc_ids or [])
        assigned_set = set(assigned_doc_ids or [])
        global_set = set(global_doc_ids or [])
        platform_set = assigned_set | global_set

        def _matches_scope(r: VectorChunkRow) -> bool:
            if doc_type and r.doc_type.upper() != doc_type.upper():
                return False
            # 1. BOOK scope (tenant + book + section/chapter anti-spoiler + optional ranges)
            if r.book_id == book_id and r.owner_id == owner_id:
                order = r.effective_order
                if order_ranges:
                    return any(lo <= order <= hi for lo, hi in order_ranges)
                if max_narrative_order is not None:
                    if order > max_narrative_order:
                        return False
                    if min_narrative_order is not None and min_narrative_order > 0:
                        return order == 0 or order >= min_narrative_order
                    return True
                return max_chapter_no is None or r.chapter_no <= max_chapter_no
            # 2. USER personal library scope (tenant + allowlist, no chapter cutoff)
            if r.book_id == PARTITION_USER and r.owner_id == owner_id and r.document_id in user_set:
                return True
            # 3. ASSIGNED / GLOBAL platform scope (Java-authorized allowlist, no chapter cutoff)
            return r.book_id == PARTITION_GLOBAL and r.document_id in platform_set

        filtered = [r for r in self._rows.values() if _matches_scope(r)]
        if not filtered:
            return []

        q_tokens = set(re.findall(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]{1,2}", query_text.lower()))

        def bm25_like(content: str) -> float:
            low = content.lower()
            return sum(1.0 for t in q_tokens if t in low)

        dense_rank = sorted(filtered, key=lambda r: _cosine(query_vector, r.dense_vec), reverse=True)
        sparse_rank = sorted(filtered, key=lambda r: bm25_like(r.content), reverse=True)

        rrf_scores: dict[str, float] = {}
        k = 60.0
        for idx, r in enumerate(dense_rank, start=1):
            rrf_scores[r.chunk_id] = rrf_scores.get(r.chunk_id, 0.0) + 1.0 / (k + idx)
        for idx, r in enumerate(sparse_rank, start=1):
            rrf_scores[r.chunk_id] = rrf_scores.get(r.chunk_id, 0.0) + 1.0 / (k + idx)

        ranked = sorted(filtered, key=lambda r: rrf_scores.get(r.chunk_id, 0.0), reverse=True)[:top_k]
        return [
            VectorSearchHit(
                chunk_id=r.chunk_id,
                parent_chunk_id=r.parent_chunk_id,
                document_id=r.document_id,
                doc_version=r.doc_version,
                chapter_no=r.chapter_no,
                doc_type=r.doc_type,
                scene_title=r.scene_title,
                content=r.content,
                score=round(rrf_scores[r.chunk_id], 5),
                scope=_classify_scope(r.document_id, user_set, assigned_set, global_set),
                narrative_order=r.effective_order,
                section_id=r.section_id,
            )
            for r in ranked
        ]

    async def health_check(self) -> bool:
        return True


class InMemorySummaryVectorStore:
    """In-memory implementation of SummaryVectorStore for deterministic Coarse-to-Fine unit tests."""

    def __init__(self) -> None:
        self._rows: dict[str, SummaryVectorRow] = {}

    async def ensure_collection(self) -> None:
        return None

    async def upsert_summaries(self, rows: list[SummaryVectorRow]) -> int:
        for r in rows:
            self._rows[r.node_id] = r
        return len(rows)

    async def delete_by_node(self, *, book_id: str, node_id: str) -> None:
        row = self._rows.get(node_id)
        if row is not None and row.book_id == book_id:
            self._rows.pop(node_id, None)

    async def search_summaries(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 4,
        max_order: int | None = None,
    ) -> list[SummarySearchHit]:
        filtered = [
            r
            for r in self._rows.values()
            if r.book_id == book_id
            and r.owner_id == owner_id
            and (max_order is None or r.end_order <= max_order)
        ]
        if not filtered:
            return []

        q_tokens = set(re.findall(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]{1,2}", query_text.lower()))

        def bm25_like(content: str) -> float:
            low = content.lower()
            return sum(1.0 for t in q_tokens if t in low)

        dense_rank = sorted(filtered, key=lambda r: _cosine(query_vector, r.dense_vec), reverse=True)
        sparse_rank = sorted(filtered, key=lambda r: bm25_like(r.content), reverse=True)

        rrf: dict[str, float] = {}
        for idx, r in enumerate(dense_rank, start=1):
            rrf[r.node_id] = rrf.get(r.node_id, 0.0) + 1.0 / (60.0 + idx)
        for idx, r in enumerate(sparse_rank, start=1):
            rrf[r.node_id] = rrf.get(r.node_id, 0.0) + 1.0 / (60.0 + idx)

        ranked = sorted(filtered, key=lambda r: rrf.get(r.node_id, 0.0), reverse=True)[:top_k]
        return [
            SummarySearchHit(
                node_id=r.node_id,
                tier_level=r.tier_level,
                start_order=r.start_order,
                end_order=r.end_order,
                chapter_no=r.chapter_no,
                content=r.content,
                score=round(rrf[r.node_id], 5),
            )
            for r in ranked
        ]
