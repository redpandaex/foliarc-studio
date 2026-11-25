from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import Any
from urllib.parse import urlparse

import aio_pika
import httpx
from aio_pika.abc import AbstractIncomingMessage
from minio import Minio
from minio.error import S3Error

from foliarc_agent.config import Settings
from foliarc_agent.rag.chunker import build_parent_child_chunks
from foliarc_agent.rag.distiller import ChapterDistiller
from foliarc_agent.rag.embeddings import EmbeddingProvider
from foliarc_agent.rag.memory_extractor import StructuredMemoryExtractor
from foliarc_agent.rag.parsers import parse_document_bytes
from foliarc_agent.rag.summarizer import HierarchicalSummarizer
from foliarc_agent.rag.vector_store import (
    SummaryVectorRow,
    SummaryVectorStore,
    VectorChunkRow,
    VectorStore,
    derive_narrative_order,
)

logger = logging.getLogger(__name__)

RAG_EXCHANGE = "foliarc.rag.exchange"
RAG_DLX = "foliarc.rag.dlx"

INGEST_QUEUE = "foliarc.rag.ingest.queue"
CHAPTER_INGEST_QUEUE = "foliarc.rag.chapter.ingest.queue"
DELETE_QUEUE = "foliarc.rag.delete.queue"
MEMORY_QUEUE = "foliarc.memory.extract.queue"
SUMMARY_QUEUE = "foliarc.memory.summary.queue"
DISTILL_QUEUE = "foliarc.memory.distill.queue"
ROLLUP_QUEUE = "foliarc.memory.rollup.queue"

INGEST_DLQ = "foliarc.rag.ingest.dlq"
CHAPTER_INGEST_DLQ = "foliarc.rag.chapter.ingest.dlq"
DELETE_DLQ = "foliarc.rag.delete.dlq"
MEMORY_DLQ = "foliarc.memory.extract.dlq"
SUMMARY_DLQ = "foliarc.memory.summary.dlq"
DISTILL_DLQ = "foliarc.memory.distill.dlq"
ROLLUP_DLQ = "foliarc.memory.rollup.dlq"

RK_INGEST = "rag.ingest"
RK_CHAPTER_INGEST = "rag.chapter.ingest"
RK_DELETE = "rag.delete"
RK_MEMORY = "memory.extract"
RK_SUMMARY = "memory.summary"
RK_DISTILL = "memory.distill"
RK_ROLLUP = "memory.rollup"


class RagIngestionWorker:
    """
    Async RabbitMQ consumer implementing:
    - Idempotent consumer deduplication via Java PG `processed_events`
    - Step-by-step document ingestion progress (`PARSING -> CHUNKING -> EMBEDDING -> READY`)
    - PG/Milvus dual-write consistency (`doc_version` guard + stale version cleanup + soft-delete purge)
    - Async chapter structured long-term memory extraction
    - Dead-Letter Queue (DLQ) routing on unrecoverable processing failures
    """

    def __init__(
        self,
        *,
        settings: Settings,
        vector_store: VectorStore,
        embedder: EmbeddingProvider,
        memory_extractor: StructuredMemoryExtractor,
        summarizer: HierarchicalSummarizer | None = None,
        distiller: ChapterDistiller | None = None,
        summary_store: SummaryVectorStore | None = None,
    ) -> None:
        self._settings = settings
        self._store = vector_store
        self._embedder = embedder
        self._extractor = memory_extractor
        self._summarizer = summarizer or HierarchicalSummarizer(settings)
        self._distiller = distiller or ChapterDistiller(settings)
        self._summary_store = summary_store
        self._task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._connected = False
        self._minio: Minio | None = None

    @property
    def connected(self) -> bool:
        return self._connected

    def _get_minio(self) -> Minio:
        if self._minio is None:
            parsed = urlparse(self._settings.minio_endpoint)
            endpoint = parsed.netloc or parsed.path
            secure = parsed.scheme == "https"
            self._minio = Minio(
                endpoint=endpoint,
                access_key=self._settings.minio_root_user,
                secret_key=self._settings.minio_root_password,
                secure=secure,
            )
        return self._minio

    def start(self) -> None:
        if not self._settings.rag_worker_enabled:
            logger.info("RAG RabbitMQ worker disabled via config")
            return
        if self._task is None or self._task.done():
            self._stop_event.clear()
            self._task = asyncio.create_task(self._run_loop(), name="foliarc-rag-rabbitmq-worker")

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None and not self._task.done():
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
        self._connected = False

    async def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            connection: aio_pika.abc.AbstractRobustConnection | None = None
            try:
                connection = await aio_pika.connect_robust(self._settings.rabbitmq_url, timeout=8)
                # Queue priority isolation: dedicated channels so heavy PDF uploads never starve real-time chapters/summaries
                fast_channel = await connection.channel()
                await fast_channel.set_qos(prefetch_count=6)
                bulk_channel = await connection.channel()
                await bulk_channel.set_qos(prefetch_count=2)

                exchange = await fast_channel.declare_exchange(
                    RAG_EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True
                )
                dlx = await fast_channel.declare_exchange(
                    RAG_DLX, aio_pika.ExchangeType.DIRECT, durable=True
                )

                for ch, q_name, dlq_name, rk, handler in (
                    (bulk_channel, INGEST_QUEUE, INGEST_DLQ, RK_INGEST, self._on_ingest_message),
                    (fast_channel, CHAPTER_INGEST_QUEUE, CHAPTER_INGEST_DLQ, RK_CHAPTER_INGEST, self._on_ingest_message),
                    (fast_channel, DELETE_QUEUE, DELETE_DLQ, RK_DELETE, self._on_delete_message),
                    (fast_channel, MEMORY_QUEUE, MEMORY_DLQ, RK_MEMORY, self._on_memory_message),
                    (fast_channel, SUMMARY_QUEUE, SUMMARY_DLQ, RK_SUMMARY, self._on_summary_message),
                    (fast_channel, DISTILL_QUEUE, DISTILL_DLQ, RK_DISTILL, self._on_distill_message),
                    (bulk_channel, ROLLUP_QUEUE, ROLLUP_DLQ, RK_ROLLUP, self._on_rollup_message),
                ):
                    dlq = await ch.declare_queue(dlq_name, durable=True)
                    await dlq.bind(dlx, routing_key=dlq_name)

                    queue = await ch.declare_queue(
                        q_name,
                        durable=True,
                        arguments={
                            "x-dead-letter-exchange": RAG_DLX,
                            "x-dead-letter-routing-key": dlq_name,
                        },
                    )
                    await queue.bind(exchange, routing_key=rk)
                    await queue.consume(handler)

                self._connected = True
                logger.info("Connected RabbitMQ RAG worker to %s:%s", self._settings.rabbitmq_host, self._settings.rabbitmq_port)
                await self._stop_event.wait()
            except asyncio.CancelledError:
                raise
            except (OSError, aio_pika.AMQPException, RuntimeError) as exc:
                self._connected = False
                logger.warning("RabbitMQ worker connection deferred (%s); retrying in 5s", exc)
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(self._stop_event.wait(), timeout=5.0)
            finally:
                self._connected = False
                if connection is not None and not connection.is_closed:
                    with contextlib.suppress(Exception):
                        await connection.close()

    # --- Message Handlers ---

    async def _on_ingest_message(self, message: AbstractIncomingMessage) -> None:
        payload: dict[str, Any] = {}
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "rag-ingest-worker"):
                logger.info("Skipping duplicate ingest message: %s", idem_key)
                await message.ack()
                return

            await self.process_ingest_payload(payload)
            await message.ack()
        except Exception as exc:
            logger.exception("Failed to process ingest message (routing to DLQ)")
            doc_id = payload.get("document_id")
            if doc_id:
                with contextlib.suppress(Exception):
                    await self._update_doc_status(
                        doc_id=str(doc_id),
                        doc_version=int(payload.get("doc_version", 1)),
                        status="FAILED",
                        progress=0,
                        error_message=str(exc)[:800],
                    )
            await message.reject(requeue=False)

    async def _on_delete_message(self, message: AbstractIncomingMessage) -> None:
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "rag-delete-worker"):
                await message.ack()
                return

            await self._store.delete_by_document(
                book_id=str(payload["book_id"]),
                document_id=str(payload["document_id"]),
                max_version=int(payload.get("doc_version", 0)) or None,
            )
            logger.info(
                "Purged Milvus vectors for deleted doc %s (<= v%s)",
                payload["document_id"],
                payload.get("doc_version"),
            )
            await message.ack()
        except Exception:
            logger.exception("Failed to process delete message (routing to DLQ)")
            await message.reject(requeue=False)

    async def _on_memory_message(self, message: AbstractIncomingMessage) -> None:
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "memory-extract-worker"):
                await message.ack()
                return

            await self.process_memory_payload(payload)
            await message.ack()
        except Exception:
            logger.exception("Failed to process memory extraction message (routing to DLQ)")
            await message.reject(requeue=False)

    async def _on_summary_message(self, message: AbstractIncomingMessage) -> None:
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "summary-worker"):
                await message.ack()
                return

            await self.process_summary_payload(payload)
            await message.ack()
        except Exception:
            logger.exception("Failed to process hierarchical summary message (routing to DLQ)")
            await message.reject(requeue=False)

    async def _on_distill_message(self, message: AbstractIncomingMessage) -> None:
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "section-distill-worker"):
                await message.ack()
                return

            await self.process_distill_payload(payload)
            await message.ack()
        except Exception:
            logger.exception("Failed to process unified section distill message (routing to DLQ)")
            await message.reject(requeue=False)

    async def _on_rollup_message(self, message: AbstractIncomingMessage) -> None:
        try:
            payload = json.loads(message.body.decode("utf-8"))
            idem_key = str(payload.get("idempotency_key") or message.headers.get("x-idempotency-key") or "")
            if idem_key and not await self._claim_idempotency(idem_key, "memory-rollup-worker"):
                await message.ack()
                return

            await self.process_rollup_payload(payload)
            await message.ack()
        except Exception:
            logger.exception("Failed to process logarithmic rollup message (routing to DLQ)")
            await message.reject(requeue=False)

    # --- Core Pipelines (also directly callable in unit/integration tests) ---

    async def process_ingest_payload(self, payload: dict[str, Any]) -> int:
        doc_id = str(payload["document_id"])
        scope = str(payload.get("scope", "BOOK")).upper()
        book_id = str(payload.get("book_id") or ("__GLOBAL__" if scope == "GLOBAL" else "__USER__"))
        owner_id = str(payload["owner_id"])
        doc_version = int(payload.get("doc_version", 1))
        source_type = str(payload.get("source_type", "UPLOAD")).upper()
        chapter_no = int(payload.get("chapter_no", 0))
        doc_type = str(payload.get("doc_type", "WORLDBOOK")).upper()
        title = str(payload.get("title", "知识库文档"))
        file_name = str(payload.get("file_name", "document.txt"))

        # Guard against stale/deleted documents before heavy work
        latest_doc = await self._fetch_doc_meta(doc_id)
        if latest_doc is not None and (
            latest_doc.get("status") == "DELETED" or int(latest_doc.get("docVersion", 1)) > doc_version
        ):
            logger.info("Skipping superseded/deleted ingest doc %s v%s", doc_id, doc_version)
            return 0

        await self._update_doc_status(doc_id=doc_id, doc_version=doc_version, status="PARSING", progress=20)

        if source_type == "CHAPTER":
            chapter_id = str(payload["source_chapter_id"])
            chapter_data = await self._fetch_chapter(chapter_id)
            raw_bytes = f"# {chapter_data['title']}\n\n{chapter_data['content']}".encode()
            file_name = f"chapter-{chapter_data['chapterNo']}.md"
            chapter_no = int(chapter_data["chapterNo"])
        else:
            bucket = str(payload.get("minio_bucket") or self._settings.minio_bucket_docs)
            object_key = str(payload["minio_object_key"])
            raw_bytes = await asyncio.to_thread(self._download_minio_bytes, bucket, object_key)

        sections = parse_document_bytes(file_name, raw_bytes)
        if not sections:
            raise ValueError(f"文档 `{file_name}` 解析后无有效文本内容")

        await self._update_doc_status(doc_id=doc_id, doc_version=doc_version, status="CHUNKING", progress=45)
        parents, children = build_parent_child_chunks(
            sections,
            chapter_no=chapter_no,
            doc_type=doc_type,
            doc_title=title,
        )
        if not children:
            raise ValueError(f"文档 `{file_name}` 切片为空")

        await self._save_chunks_to_pg(doc_id=doc_id, doc_version=doc_version, chunks=[*parents, *children])

        await self._update_doc_status(
            doc_id=doc_id,
            doc_version=doc_version,
            status="EMBEDDING",
            progress=75,
            chunk_count=len(children),
        )
        section_id = str(payload.get("section_id") or "")
        narrative_order = int(payload.get("narrative_order") or 0)
        vectors = await self._embedder.embed_texts([c.content for c in children])
        rows = [
            VectorChunkRow(
                chunk_id=c.id,
                book_id=book_id,
                owner_id=owner_id,
                document_id=doc_id,
                doc_version=doc_version,
                parent_chunk_id=c.parent_chunk_id or c.id,
                chapter_no=c.chapter_no,
                doc_type=doc_type,
                scene_title=c.scene_title,
                content=c.content,
                dense_vec=vec,
                scope=scope,
                narrative_order=narrative_order or derive_narrative_order(c.chapter_no),
                section_id=section_id,
            )
            for c, vec in zip(children, vectors, strict=True)
        ]

        await self._store.upsert_chunks(rows)
        await self._store.delete_stale_versions(
            book_id=book_id,
            document_id=doc_id,
            keep_version=doc_version,
        )

        # Final race-condition check: if deleted concurrently while embedding, immediately clean up Milvus!
        final_doc = await self._fetch_doc_meta(doc_id)
        if final_doc is not None and final_doc.get("status") == "DELETED":
            await self._store.delete_by_document(book_id=book_id, document_id=doc_id)
            logger.info("Document %s deleted during ingestion; rolled back Milvus vectors", doc_id)
            return 0

        await self._update_doc_status(
            doc_id=doc_id,
            doc_version=doc_version,
            status="READY",
            progress=100,
            chunk_count=len(children),
        )
        logger.info("Ingested doc %s v%s: %d parents, %d children", doc_id, doc_version, len(parents), len(children))
        return len(children)

    async def process_memory_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        book_id = str(payload["book_id"])
        owner_id = str(payload["owner_id"])
        chapter_id = str(payload["chapter_id"])

        chapter_data = await self._fetch_chapter(chapter_id)
        bible = await self._fetch_bible(owner_id, book_id)
        extracted = await self._extractor.extract(
            chapter_no=int(chapter_data["chapterNo"]),
            chapter_title=str(chapter_data["title"]),
            chapter_content=str(chapter_data["content"]),
            existing_bible=bible,
        )
        applied = await self._apply_memory(owner_id, book_id, extracted)
        logger.info("Applied extracted memory for book %s ch.%s: %s", book_id, chapter_data["chapterNo"], applied)
        return applied

    async def process_summary_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Generates Chapter Summary, and conditionally rolls up Volume Summary & Auto-Emergent L0 Charter."""
        book_id = str(payload["book_id"])
        owner_id = str(payload["owner_id"])
        chapter_id = str(payload["chapter_id"])
        content_hash = str(payload.get("content_hash") or "")

        chapter_data = await self._fetch_chapter(chapter_id)
        bible = await self._fetch_bible(owner_id, book_id)
        known_chars = [c.get("name", "") for c in (bible or {}).get("characters", []) if c.get("name")]

        summary = await self._summarizer.summarize_chapter(
            chapter_no=int(chapter_data["chapterNo"]),
            chapter_title=str(chapter_data["title"]),
            chapter_content=str(chapter_data["content"]),
            known_characters=known_chars,
        )
        save_resp = await self._save_chapter_summary(
            book_id=book_id,
            payload={
                "chapterId": chapter_id,
                "chapterNo": int(chapter_data["chapterNo"]),
                "chapterTitle": str(chapter_data["title"]),
                "contentHash": content_hash,
                **summary,
            },
        )

        book_summaries: dict[str, Any] | None = None
        if save_resp.get("refreshVolumeSummary") or save_resp.get("refreshAutoCharter"):
            book_summaries = await self._fetch_book_summaries(book_id)

        if save_resp.get("refreshVolumeSummary") and book_summaries is not None:
            vol_no = int(save_resp.get("volumeNo", 1))
            start_no = int(save_resp.get("volumeStartChapterNo", 1))
            end_no = int(save_resp.get("volumeEndChapterNo", start_no + 9))
            vol_chapters = [
                c
                for c in book_summaries.get("chapterSummaries", [])
                if start_no <= int(c.get("chapterNo", 0)) <= end_no
            ]
            if vol_chapters:
                vol_text, src_hash = await self._summarizer.summarize_volume(
                    volume_no=vol_no,
                    start_chapter_no=start_no,
                    end_chapter_no=end_no,
                    chapter_summaries=vol_chapters,
                )
                await self._save_volume_summary(
                    book_id=book_id,
                    payload={
                        "volumeNo": vol_no,
                        "startChapterNo": start_no,
                        "endChapterNo": end_no,
                        "summaryText": vol_text,
                        "sourceHash": src_hash,
                    },
                )

        if save_resp.get("refreshAutoCharter") and book_summaries is not None:
            charter_text = await self._summarizer.infer_story_charter(
                book_title=str(book_summaries.get("bookTitle") or ""),
                genre=book_summaries.get("genre"),
                existing_synopsis=book_summaries.get("synopsis"),
                chapter_summaries=book_summaries.get("chapterSummaries", []),
                known_characters=known_chars,
            )
            await self._save_auto_charter(
                book_id=book_id,
                auto_charter=charter_text,
                chapter_count=int(save_resp.get("totalSummariesCount", 1)),
            )

        logger.info("Processed hierarchical summary for book %s ch.%s: %s", book_id, chapter_data["chapterNo"], save_resp)
        return save_resp

    async def process_distill_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Phase 5.8A unified single-pass section compilation (Summary + Memory + summary_vectors)."""
        book_id = str(payload["book_id"])
        owner_id = str(payload["owner_id"])
        section_id = str(payload["section_id"])

        section_data = await self._fetch_section(section_id)
        bible = await self._fetch_bible(owner_id, book_id)
        known_chars = [str(c.get("name", "")) for c in (bible or {}).get("characters", []) if c.get("name")]
        open_clues = [
            f"{f.get('title', '')}：{f.get('description', '')}"
            for f in (bible or {}).get("foreshadowings", [])
            if str(f.get("status", "")).upper() in ("OPEN", "PLANNED", "PLANTED", "HINTED")
        ]

        distilled = await self._distiller.distill_section(
            chapter_no=int(section_data.get("chapterNo", 1)),
            section_no=int(section_data.get("sectionNo", 1)),
            chapter_title=str(section_data.get("chapterTitle") or ""),
            section_title=str(section_data.get("sectionTitle") or ""),
            content=str(section_data.get("content") or ""),
            volume_arc_goal=str(section_data.get("volumeArcGoal") or ""),
            prev_ending_hook=str(section_data.get("prevEndingHook") or ""),
            known_characters=known_chars,
            open_foreshadowings=open_clues,
            genre=section_data.get("genre"),
        )
        summary = distilled["summary"]
        ch_no = int(section_data.get("chapterNo", 1))
        narrative_order = int(
            section_data.get("narrativeOrder")
            or derive_narrative_order(ch_no)
        )
        raw_mem = distilled.get("memory") or {}
        projected_memory = {
            "chapterNo": ch_no,
            "characterUpdates": [
                {
                    "name": str(c.get("name") or "").strip(),
                    "currentLocation": str(c.get("currentLocation") or "").strip() or None,
                    "statusNotes": str(c.get("statusNotes") or "").strip() or None,
                    "inventory": str(c.get("inventoryDelta") or c.get("inventory") or "").strip() or None,
                }
                for c in (raw_mem.get("characterUpdates") or [])
                if str(c.get("name") or "").strip()
            ],
            "foreshadowingUpdates": [
                {
                    "title": str(f.get("title") or "").strip(),
                    "description": str(f.get("description") or f.get("quote") or "").strip() or None,
                    "plantedChapterNo": ch_no if str(f.get("action", "")).upper() != "RESOLVED" else None,
                    "resolvedChapterNo": ch_no if str(f.get("action", "")).upper() == "RESOLVED" else None,
                    "status": "RESOLVED" if str(f.get("action", "")).upper() == "RESOLVED" else "OPEN",
                }
                for f in (raw_mem.get("foreshadowingSignals") or [])
                if str(f.get("title") or "").strip()
            ],
            "relations": [
                {
                    "sourceCharacter": str(r.get("sourceCharacter") or "").strip(),
                    "targetCharacter": str(r.get("targetCharacter") or "").strip(),
                    "relationType": str(r.get("relationType") or "关联").strip(),
                    "description": str(r.get("description") or "").strip() or None,
                    "sinceChapterNo": ch_no,
                }
                for r in (raw_mem.get("relations") or [])
                if str(r.get("sourceCharacter") or "").strip() and str(r.get("targetCharacter") or "").strip()
            ],
            "timelineEvents": [
                {
                    "chapterNo": ch_no,
                    "eventTitle": str(t.get("eventTitle") or "").strip(),
                    "summary": str(t.get("summary") or "").strip() or None,
                    "involvedCharacters": str(t.get("involvedCharacters") or "").strip() or None,
                    "location": str(t.get("location") or "").strip() or None,
                }
                for t in (raw_mem.get("timelineEvents") or [])
                if str(t.get("eventTitle") or "").strip()
            ],
        }

        apply_resp = await self._apply_section_distill(
            owner_id=owner_id,
            book_id=book_id,
            payload={
                "sectionId": section_id,
                "contentHash": str(section_data.get("contentHash") or ""),
                "summary": summary,
                "memory": projected_memory,
            },
        )

        # Index Tier-0 section summary into foliarc_summary_vectors for Coarse-to-Fine routing
        if self._summary_store is not None and summary.get("summaryText"):
            summary_doc = f"第{section_data.get('chapterNo')}章第{section_data.get('sectionNo', 1)}节：{summary['summaryText']}"
            if summary.get("stateChanges"):
                summary_doc += f" 硬事实：{summary['stateChanges']}"
            if summary.get("clueSignals"):
                summary_doc += f" 伏笔：{summary['clueSignals']}"
            vecs = await self._embedder.embed_texts([summary_doc])
            await self._summary_store.upsert_summaries(
                [
                    SummaryVectorRow(
                        node_id=f"sec:{section_id}",
                        book_id=book_id,
                        owner_id=owner_id,
                        tier_level=0,
                        start_order=narrative_order,
                        end_order=narrative_order,
                        chapter_no=int(section_data.get("chapterNo", 1)),
                        content=summary_doc,
                        dense_vec=vecs[0],
                    )
                ]
            )

        logger.info("Compiled unified section distill for book %s sec %s: %s", book_id, section_id, apply_resp)
        return apply_resp

    async def process_rollup_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Phase 5.8A logarithmic Tier 1/2/3 rollup synthesis + prose-sampled L0 auto-charter refresh."""
        book_id = str(payload["book_id"])
        owner_id = str(payload.get("owner_id") or "")
        rollups_data = await self._fetch_book_rollups(book_id)
        if not owner_id:
            owner_id = str(rollups_data.get("ownerId") or "")

        section_sums: list[dict[str, Any]] = list(rollups_data.get("sectionSummaries") or [])
        saved_nodes: list[dict[str, Any]] = []

        # Tier 1: every 10 sections (or target seq_no from payload)
        target_tier = int(payload.get("tier_level") or 1)
        seq_no = int(payload.get("seq_no") or 1)
        if target_tier == 1:
            start_order = int(payload.get("start_order") or 0)
            end_order = int(payload.get("end_order") or 0)
            group = [
                s
                for s in section_sums
                if (start_order <= 0 or int(s.get("narrativeOrder", 0)) >= start_order)
                and (end_order <= 0 or int(s.get("narrativeOrder", 0)) <= end_order)
            ]
            if not group:
                group = section_sums[(seq_no - 1) * 10 : seq_no * 10]
            if group:
                res = await self._distiller.summarize_rollup(
                    tier_level=1,
                    seq_no=seq_no,
                    start_chapter_no=int(group[0].get("chapterNo", 1)),
                    end_chapter_no=int(group[-1].get("chapterNo", 1)),
                    items=group,
                    title=str(payload.get("title") or f"第 {seq_no} 卷"),
                )
                saved = await self._save_rollup(
                    book_id=book_id,
                    payload={
                        "tierLevel": 1,
                        "seqNo": seq_no,
                        "volumeId": payload.get("volume_id"),
                        "startOrder": int(group[0].get("narrativeOrder", 1001)),
                        "endOrder": int(group[-1].get("narrativeOrder", 1001)),
                        "startChapterNo": int(group[0].get("chapterNo", 1)),
                        "endChapterNo": int(group[-1].get("chapterNo", 1)),
                        "title": str(payload.get("title") or f"第 {seq_no} 卷"),
                        "summaryText": res["summaryText"],
                        "openThreads": res["openThreads"],
                        "sourceHash": res["sourceHash"],
                    },
                )
                saved_nodes.append(saved)
                await self._index_rollup_vector(book_id, owner_id, saved, res["summaryText"])
        elif target_tier in (2, 3):
            lower_tier = target_tier - 1
            lower_items = [
                r
                for r in (rollups_data.get("rollups") or [])
                if int(r.get("tierLevel", 0)) == lower_tier
            ]
            group_size = 10 if target_tier == 2 else 5
            chunk = lower_items[(seq_no - 1) * group_size : seq_no * group_size]
            if chunk:
                res = await self._distiller.summarize_rollup(
                    tier_level=target_tier,
                    seq_no=seq_no,
                    start_chapter_no=int(chunk[0].get("startChapterNo", 1)),
                    end_chapter_no=int(chunk[-1].get("endChapterNo", 1)),
                    items=chunk,
                    title=str(payload.get("title") or f"第 {seq_no} {'篇' if target_tier == 2 else '纪元'}"),
                )
                saved = await self._save_rollup(
                    book_id=book_id,
                    payload={
                        "tierLevel": target_tier,
                        "seqNo": seq_no,
                        "startOrder": int(chunk[0].get("startOrder", 1001)),
                        "endOrder": int(chunk[-1].get("endOrder", 1001)),
                        "startChapterNo": int(chunk[0].get("startChapterNo", 1)),
                        "endChapterNo": int(chunk[-1].get("endChapterNo", 1)),
                        "title": str(payload.get("title") or f"第 {seq_no} {'篇' if target_tier == 2 else '纪元'}"),
                        "summaryText": res["summaryText"],
                        "openThreads": res["openThreads"],
                        "sourceHash": res["sourceHash"],
                    },
                )
                saved_nodes.append(saved)
                await self._index_rollup_vector(book_id, owner_id, saved, res["summaryText"])

        if payload.get("refresh_auto_charter") and rollups_data.get("charterSource") != "AUTHOR_DEFINED":
            bible = await self._fetch_bible(owner_id, book_id) if owner_id else None
            known_chars = [str(c.get("name", "")) for c in (bible or {}).get("characters", []) if c.get("name")]
            charter_text = await self._distiller.infer_story_charter(
                book_title=str(rollups_data.get("bookTitle") or ""),
                genre=rollups_data.get("genre"),
                existing_synopsis=rollups_data.get("synopsis"),
                existing_auto_charter=str(rollups_data.get("existingAutoCharter") or ""),
                rollup_summaries=list(rollups_data.get("rollups") or []),
                recent_summaries=section_sums[-3:],
                first_section_sample=str(rollups_data.get("firstSectionSample") or ""),
                latest_section_sample=str(rollups_data.get("latestSectionSample") or ""),
                known_characters=known_chars,
            )
            await self._save_auto_charter(
                book_id=book_id,
                auto_charter=charter_text,
                chapter_count=len(section_sums) or 1,
            )

        return {"savedNodes": saved_nodes}

    async def _index_rollup_vector(
        self,
        book_id: str,
        owner_id: str,
        saved: dict[str, Any],
        summary_text: str,
    ) -> None:
        if self._summary_store is None or not summary_text:
            return
        vecs = await self._embedder.embed_texts([summary_text])
        node_id = str(saved.get("nodeId") or f"t{saved.get('tierLevel', 1)}:{saved.get('seqNo', 1)}")
        await self._summary_store.upsert_summaries(
            [
                SummaryVectorRow(
                    node_id=node_id,
                    book_id=book_id,
                    owner_id=owner_id,
                    tier_level=int(saved.get("tierLevel", 1)),
                    start_order=int(saved.get("startOrder", 1001)),
                    end_order=int(saved.get("endOrder", 1001)),
                    chapter_no=int(saved.get("startChapterNo", 1)),
                    content=summary_text,
                    dense_vec=vecs[0],
                )
            ]
        )

    # --- Internal HTTP & MinIO Helpers ---

    def _download_minio_bytes(self, bucket: str, object_key: str) -> bytes:
        client = self._get_minio()
        response = None
        try:
            response = client.get_object(bucket, object_key)
            return response.read()
        except S3Error as exc:
            raise RuntimeError(f"MinIO 下载失败 ({bucket}/{object_key}): {exc}") from exc
        finally:
            if response is not None:
                response.close()
                response.release_conn()

    def _internal_headers(self, owner_id: str | None = None) -> dict[str, str]:
        headers = {"X-Internal-Token": self._settings.internal_service_token}
        if owner_id:
            headers["X-Owner-Id"] = owner_id
        return headers

    async def _claim_idempotency(self, idempotency_key: str, consumer_name: str) -> bool:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/outbox/claim-idempotency"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                url,
                headers=self._internal_headers(),
                json={"idempotencyKey": idempotency_key, "consumerName": consumer_name},
            )
            resp.raise_for_status()
            return bool(resp.json().get("claimed", False))

    async def _fetch_doc_meta(self, doc_id: str) -> dict[str, Any] | None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/documents/{doc_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers())
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            return resp.json()

    async def _update_doc_status(
        self,
        *,
        doc_id: str,
        doc_version: int,
        status: str,
        progress: int,
        chunk_count: int | None = None,
        error_message: str | None = None,
    ) -> None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/documents/{doc_id}/status"
        body: dict[str, Any] = {
            "docVersion": doc_version,
            "status": status,
            "progress": progress,
            "errorMessage": error_message,
        }
        if chunk_count is not None:
            body["chunkCount"] = chunk_count
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=self._internal_headers(), json=body)
            resp.raise_for_status()

    async def _save_chunks_to_pg(self, *, doc_id: str, doc_version: int, chunks: list[Any]) -> None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/documents/{doc_id}/chunks"
        payload = {
            "docVersion": doc_version,
            "chunks": [
                {
                    "id": c.id,
                    "chunkLevel": c.chunk_level,
                    "parentChunkId": c.parent_chunk_id,
                    "chunkIndex": c.chunk_index,
                    "chapterNo": c.chapter_no,
                    "sceneTitle": c.scene_title,
                    "content": c.content,
                    "tokenCount": c.token_count,
                    "metadataJson": c.metadata_json,
                }
                for c in chunks
            ],
        }
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(url, headers=self._internal_headers(), json=payload)
            resp.raise_for_status()

    async def _fetch_chapter(self, chapter_id: str) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/chapters/{chapter_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers())
            resp.raise_for_status()
            return resp.json()

    async def _fetch_bible(self, owner_id: str, book_id: str) -> dict[str, Any] | None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/books/{book_id}/lore/bible"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers(owner_id))
            if resp.status_code != 200:
                return None
            return resp.json()

    async def _apply_memory(self, owner_id: str, book_id: str, extracted: dict[str, Any]) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/memory/apply"
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, headers=self._internal_headers(owner_id), json=extracted)
            resp.raise_for_status()
            return resp.json()

    async def _fetch_book_summaries(self, book_id: str) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/summaries"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers())
            resp.raise_for_status()
            return resp.json()

    async def _save_chapter_summary(self, *, book_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/summaries/chapter"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=self._internal_headers(), json=payload)
            resp.raise_for_status()
            return resp.json()

    async def _save_volume_summary(self, *, book_id: str, payload: dict[str, Any]) -> None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/summaries/volume"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=self._internal_headers(), json=payload)
            resp.raise_for_status()

    async def _save_auto_charter(self, *, book_id: str, auto_charter: str, chapter_count: int) -> None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/charter/auto"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                url,
                headers=self._internal_headers(),
                json={"autoCharter": auto_charter, "chapterCount": chapter_count},
            )
            resp.raise_for_status()

    async def _fetch_section(self, section_id: str) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/sections/{section_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers())
            resp.raise_for_status()
            return resp.json()

    async def _apply_section_distill(
        self, *, owner_id: str, book_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/distill/apply"
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, headers=self._internal_headers(owner_id), json=payload)
            resp.raise_for_status()
            return resp.json()

    async def _fetch_book_rollups(self, book_id: str) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/rollups"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._internal_headers())
            resp.raise_for_status()
            return resp.json()

    async def _save_rollup(self, *, book_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{book_id}/rollups/save"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=self._internal_headers(), json=payload)
            resp.raise_for_status()
            return resp.json()
