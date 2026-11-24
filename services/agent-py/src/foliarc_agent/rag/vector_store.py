from __future__ import annotations

import asyncio
import math
import re
from dataclasses import dataclass
from typing import Any, Protocol

from pymilvus import AnnSearchRequest, DataType, Function, FunctionType, MilvusClient, RRFRanker
from pymilvus.exceptions import MilvusException

from foliarc_agent.config import Settings


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


class VectorStore(Protocol):
    """Abstract vector storage protocol decoupling Hybrid RAG from concrete engines."""

    async def ensure_collection(self) -> None: ...

    async def upsert_chunks(self, rows: list[VectorChunkRow]) -> int: ...

    async def delete_stale_versions(self, *, book_id: str, document_id: str, keep_version: int) -> None: ...

    async def delete_by_document(self, *, book_id: str, document_id: str, max_version: int | None = None) -> None: ...

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        doc_type: str | None = None,
    ) -> list[VectorSearchHit]: ...

    async def health_check(self) -> bool: ...


def _escape_expr(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


class MilvusVectorStore:
    """
    Enterprise Milvus Standalone VectorStore:
    - Partition Key multi-tenancy on `book_id`
    - Dense HNSW (COSINE) + Native BM25 Sparse Inverted Index via `Function(FunctionType.BM25)`
    - Native `RRFRanker(k=60)` hybrid search fusion
    - Mandatory scalar pre-filtering (`book_id`, `owner_id`, `chapter_no <= N` anti-spoiler guard)
    - Monotonic `doc_version` cleanup for PG/Milvus dual-write consistency
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

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        doc_type: str | None = None,
    ) -> list[VectorSearchHit]:
        await self.ensure_collection()
        clauses = [
            f'book_id == "{_escape_expr(book_id)}"',
            f'owner_id == "{_escape_expr(owner_id)}"',
        ]
        if max_chapter_no is not None and max_chapter_no >= 0:
            clauses.append(f"chapter_no <= {int(max_chapter_no)}")
        if doc_type:
            clauses.append(f'doc_type == "{_escape_expr(doc_type.upper())}"')
        expr = " and ".join(clauses)

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
                hits.append(
                    VectorSearchHit(
                        chunk_id=str(entity.get("chunk_id", item.get("id", ""))),
                        parent_chunk_id=str(entity.get("parent_chunk_id", "")),
                        document_id=str(entity.get("document_id", "")),
                        doc_version=int(entity.get("doc_version", 1)),
                        chapter_no=int(entity.get("chapter_no", 0)),
                        doc_type=str(entity.get("doc_type", "WORLDBOOK")),
                        scene_title=str(entity.get("scene_title", "")),
                        content=str(entity.get("content", "")),
                        score=score,
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

    async def hybrid_search(
        self,
        *,
        owner_id: str,
        book_id: str,
        query_text: str,
        query_vector: list[float],
        top_k: int = 10,
        max_chapter_no: int | None = None,
        doc_type: str | None = None,
    ) -> list[VectorSearchHit]:
        filtered = [
            r
            for r in self._rows.values()
            if r.owner_id == owner_id
            and r.book_id == book_id
            and (max_chapter_no is None or r.chapter_no <= max_chapter_no)
            and (not doc_type or r.doc_type.upper() == doc_type.upper())
        ]
        if not filtered:
            return []

        def cosine(a: list[float], b: list[float]) -> float:
            dot = sum(x * y for x, y in zip(a, b, strict=False))
            na = math.sqrt(sum(x * x for x in a)) or 1.0
            nb = math.sqrt(sum(y * y for y in b)) or 1.0
            return dot / (na * nb)

        q_tokens = set(re.findall(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]{1,2}", query_text.lower()))

        def bm25_like(content: str) -> float:
            low = content.lower()
            return sum(1.0 for t in q_tokens if t in low)

        dense_rank = sorted(filtered, key=lambda r: cosine(query_vector, r.dense_vec), reverse=True)
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
            )
            for r in ranked
        ]

    async def health_check(self) -> bool:
        return True
