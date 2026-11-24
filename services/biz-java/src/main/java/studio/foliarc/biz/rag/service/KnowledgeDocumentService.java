package studio.foliarc.biz.rag.service;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.entity.DocumentChunkEntity;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.DocumentChunkMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.storage.MinioStorageService;

@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final Set<String> ALLOWED_EXTS = Set.of(".pdf", ".docx", ".md", ".txt");
    private static final Set<String> VALID_DOC_TYPES = Set.of("WORLDBOOK", "CHAPTER", "STYLE_REF", "OUTLINE");

    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioStorageService minioStorage;
    private final OutboxService outboxService;
    private final RestClient agentRestClient;

    public KnowledgeDocumentService(
            BookService bookService,
            ChapterMapper chapterMapper,
            KnowledgeDocumentMapper documentMapper,
            DocumentChunkMapper chunkMapper,
            MinioStorageService minioStorage,
            OutboxService outboxService,
            RestClient agentRestClient
    ) {
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.minioStorage = minioStorage;
        this.outboxService = outboxService;
        this.agentRestClient = agentRestClient;
    }

    public List<RagDtos.KnowledgeDocumentResponse> listDocuments(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getBookId, bookId)
                .isNull(KnowledgeDocumentEntity::getDeletedAt)
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt);
        return documentMapper.selectList(query).stream().map(KnowledgeDocumentService::toResponse).toList();
    }

    public RagDtos.KnowledgeDocumentResponse getDocument(UUID ownerId, UUID bookId, UUID docId) {
        return toResponse(requireOwnedDocument(ownerId, bookId, docId));
    }

    @Transactional
    public RagDtos.KnowledgeDocumentResponse uploadDocument(
            UUID ownerId,
            UUID bookId,
            MultipartFile file,
            String docTypeRaw,
            Integer chapterNo,
            String titleOverride
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("EMPTY_FILE", "请上传非空文档文件");
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document.txt";
        String ext = extractExtension(originalName);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw BizException.badRequest("UNSUPPORTED_FILE_TYPE", "仅支持 PDF / DOCX / MD / TXT 格式，当前: " + ext);
        }

        String docType = normalizeDocType(docTypeRaw, "WORLDBOOK");
        int effectiveChapter = chapterNo != null && chapterNo >= 0 ? chapterNo : 0;
        UUID docId = UUID.randomUUID();
        String safeFileName = originalName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String objectKey = "books/" + bookId + "/docs/" + docId + "/v1/" + safeFileName;

        try {
            minioStorage.upload(objectKey, file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw BizException.badRequest("FILE_READ_ERROR", "读取上传文件流失败: " + ex.getMessage());
        }

        String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride.trim()
                : stripExtension(originalName);

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(docId);
        entity.setBookId(bookId);
        entity.setOwnerId(ownerId);
        entity.setSourceType("UPLOAD");
        entity.setChapterNo(effectiveChapter);
        entity.setDocType(docType);
        entity.setTitle(title);
        entity.setFileName(originalName);
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        entity.setFileSize(file.getSize());
        entity.setMinioBucket(minioStorage.getDefaultBucket());
        entity.setMinioObjectKey(objectKey);
        entity.setDocVersion(1);
        entity.setStatus("QUEUED");
        entity.setProgress(0);
        entity.setChunkCount(0);
        documentMapper.insert(entity);

        enqueueIngestEvent(entity);
        return toResponse(entity);
    }

    @Transactional
    public RagDtos.KnowledgeDocumentResponse reprocessDocument(UUID ownerId, UUID bookId, UUID docId) {
        KnowledgeDocumentEntity doc = requireOwnedDocument(ownerId, bookId, docId);
        int nextVersion = (doc.getDocVersion() == null ? 1 : doc.getDocVersion()) + 1;
        doc.setDocVersion(nextVersion);
        doc.setStatus("QUEUED");
        doc.setProgress(0);
        doc.setErrorMessage(null);
        documentMapper.updateById(doc);

        enqueueIngestEvent(doc);
        return toResponse(doc);
    }

    @Transactional
    public void deleteDocument(UUID ownerId, UUID bookId, UUID docId) {
        KnowledgeDocumentEntity doc = requireOwnedDocument(ownerId, bookId, docId);
        softDeleteAndEnqueue(doc);
    }

    @Transactional
    public void deleteBySourceChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        KnowledgeDocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getBookId, bookId)
                        .eq(KnowledgeDocumentEntity::getSourceChapterId, chapterId)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );
        if (doc != null && ownerId.equals(doc.getOwnerId())) {
            softDeleteAndEnqueue(doc);
        }
    }

    @Transactional
    public RagDtos.SyncChapterResponse syncChapter(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            boolean extractMemory
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        String text = chapter.getContent() == null ? "" : chapter.getContent();
        if (text.isBlank()) {
            throw BizException.badRequest("EMPTY_CHAPTER_CONTENT", "章节正文为空，无法同步索引或抽取记忆");
        }

        KnowledgeDocumentEntity existing = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getBookId, bookId)
                        .eq(KnowledgeDocumentEntity::getSourceChapterId, chapterId)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );

        KnowledgeDocumentEntity doc;
        if (existing == null) {
            doc = new KnowledgeDocumentEntity();
            doc.setId(UUID.randomUUID());
            doc.setBookId(bookId);
            doc.setOwnerId(ownerId);
            doc.setSourceType("CHAPTER");
            doc.setSourceChapterId(chapterId);
            doc.setChapterNo(chapter.getChapterNo());
            doc.setDocType("CHAPTER");
            doc.setTitle("第" + chapter.getChapterNo() + "章 " + chapter.getTitle());
            doc.setFileName("chapter-" + chapter.getChapterNo() + ".md");
            doc.setContentType("text/markdown");
            doc.setFileSize((long) text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            doc.setMinioBucket("");
            doc.setMinioObjectKey("");
            doc.setDocVersion(1);
            doc.setStatus("QUEUED");
            doc.setProgress(0);
            doc.setChunkCount(0);
            documentMapper.insert(doc);
        } else {
            existing.setChapterNo(chapter.getChapterNo());
            existing.setTitle("第" + chapter.getChapterNo() + "章 " + chapter.getTitle());
            existing.setFileSize((long) text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            existing.setDocVersion((existing.getDocVersion() == null ? 1 : existing.getDocVersion()) + 1);
            existing.setStatus("QUEUED");
            existing.setProgress(0);
            existing.setErrorMessage(null);
            documentMapper.updateById(existing);
            doc = existing;
        }

        enqueueIngestEvent(doc);

        if (extractMemory) {
            enqueueMemoryExtractEvent(ownerId, bookId, chapter, doc.getDocVersion());
        }

        return new RagDtos.SyncChapterResponse(toResponse(doc), extractMemory);
    }

    /**
     * Silently triggers background RAG indexing + structured memory extraction when a chapter
     * has meaningful new/changed content, keeping RAG fully automatic for writers.
     */
    public void syncChapterInBackgroundIfNeeded(UUID ownerId, UUID bookId, ChapterEntity chapter) {
        if (chapter == null || chapter.getContent() == null) {
            return;
        }
        String text = chapter.getContent().trim();
        if (text.length() < 60) {
            return;
        }
        try {
            long byteLen = chapter.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            KnowledgeDocumentEntity existing = documentMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .eq(KnowledgeDocumentEntity::getBookId, bookId)
                            .eq(KnowledgeDocumentEntity::getSourceChapterId, chapter.getId())
                            .isNull(KnowledgeDocumentEntity::getDeletedAt)
            );
            if (existing != null) {
                String st = existing.getStatus() == null ? "" : existing.getStatus();
                boolean inFlight = Set.of("QUEUED", "PARSING", "CHUNKING", "EMBEDDING").contains(st);
                if (inFlight && existing.getUpdatedAt() != null
                        && existing.getUpdatedAt().isAfter(OffsetDateTime.now().minusSeconds(30))) {
                    return;
                }
                long prevSize = existing.getFileSize() == null ? 0L : existing.getFileSize();
                if (!"FAILED".equals(st) && Math.abs(byteLen - prevSize) < 180) {
                    return;
                }
            }
            syncChapter(ownerId, bookId, chapter.getId(), true);
        } catch (Exception ex) {
            log.warn("Background chapter RAG/memory sync skipped for chapter {}: {}", chapter.getId(), ex.getMessage());
        }
    }

    @Transactional
    public void triggerMemoryExtractOnly(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        if (chapter.getContent() == null || chapter.getContent().isBlank()) {
            throw BizException.badRequest("EMPTY_CHAPTER_CONTENT", "章节正文为空，无法抽取记忆");
        }
        enqueueMemoryExtractEvent(ownerId, bookId, chapter, (int) (System.currentTimeMillis() / 1000));
    }

    // --- Internal Worker Callbacks (Dual-Write Monotonic Version Guards) ---

    @Transactional
    public KnowledgeDocumentEntity updateIngestionStatus(UUID docId, RagDtos.InternalStatusUpdateRequest req) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        int incomingVersion = req.docVersion() != null ? req.docVersion() : 1;
        int currentVersion = doc.getDocVersion() != null ? doc.getDocVersion() : 1;
        if (doc.getDeletedAt() != null || incomingVersion < currentVersion) {
            log.info("Ignored stale status update for doc {} (incoming v{} < current v{}, deleted={})",
                    docId, incomingVersion, currentVersion, doc.getDeletedAt() != null);
            return doc;
        }
        doc.setStatus(req.status().trim().toUpperCase(Locale.ROOT));
        if (req.progress() != null) {
            doc.setProgress(Math.max(0, Math.min(100, req.progress())));
        }
        if (req.chunkCount() != null) {
            doc.setChunkCount(req.chunkCount());
        }
        doc.setErrorMessage(req.errorMessage());
        documentMapper.updateById(doc);
        return doc;
    }

    @Transactional
    public int saveDocumentChunks(UUID docId, RagDtos.InternalSaveChunksRequest req) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        int incomingVersion = req.docVersion() != null ? req.docVersion() : 1;
        int currentVersion = doc.getDocVersion() != null ? doc.getDocVersion() : 1;
        if (doc.getDeletedAt() != null || incomingVersion < currentVersion) {
            log.info("Ignored stale chunks save for doc {} (incoming v{} < current v{})",
                    docId, incomingVersion, currentVersion);
            return 0;
        }

        // Replace previous chunks for this document in PG cleanly inside transaction
        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, docId));

        if (req.chunks() == null || req.chunks().isEmpty()) {
            return 0;
        }

        // Insert PARENT chunks first so foreign key parent_chunk_id is satisfied for CHILD chunks
        List<RagDtos.InternalChunkInput> parents = new ArrayList<>();
        List<RagDtos.InternalChunkInput> children = new ArrayList<>();
        for (RagDtos.InternalChunkInput c : req.chunks()) {
            if ("PARENT".equalsIgnoreCase(c.chunkLevel())) {
                parents.add(c);
            } else {
                children.add(c);
            }
        }
        int inserted = 0;
        for (RagDtos.InternalChunkInput c : parents) {
            insertChunkRow(doc, incomingVersion, c);
            inserted++;
        }
        for (RagDtos.InternalChunkInput c : children) {
            insertChunkRow(doc, incomingVersion, c);
            inserted++;
        }
        return inserted;
    }

    public List<RagDtos.InternalParentChunkResponse> loadParentChunks(UUID ownerId, UUID bookId, List<UUID> parentIds) {
        bookService.requireOwnedBook(ownerId, bookId);
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        List<DocumentChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .eq(DocumentChunkEntity::getBookId, bookId)
                        .eq(DocumentChunkEntity::getOwnerId, ownerId)
                        .in(DocumentChunkEntity::getId, parentIds)
        );
        if (chunks.isEmpty()) {
            return List.of();
        }
        Set<UUID> docIds = chunks.stream().map(DocumentChunkEntity::getDocumentId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, KnowledgeDocumentEntity> docs = new HashMap<>();
        for (KnowledgeDocumentEntity d : documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .in(KnowledgeDocumentEntity::getId, docIds)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        )) {
            docs.put(d.getId(), d);
        }

        List<RagDtos.InternalParentChunkResponse> result = new ArrayList<>();
        for (DocumentChunkEntity c : chunks) {
            KnowledgeDocumentEntity doc = docs.get(c.getDocumentId());
            // Dual-write defense-in-depth: skip chunks belonging to soft-deleted or superseded doc versions!
            if (doc == null || !java.util.Objects.equals(doc.getDocVersion(), c.getDocVersion())) {
                continue;
            }
            result.add(new RagDtos.InternalParentChunkResponse(
                    c.getId(),
                    c.getDocumentId(),
                    doc.getTitle(),
                    doc.getDocType(),
                    c.getDocVersion(),
                    c.getChapterNo(),
                    c.getSceneTitle(),
                    c.getContent()
            ));
        }
        return result;
    }

    public RagDtos.InternalChapterPayload loadChapterForWorker(UUID chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        var book = bookService.getBookByIdUnchecked(chapter.getBookId());
        return new RagDtos.InternalChapterPayload(
                chapter.getId(),
                chapter.getBookId(),
                book.getOwnerId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                chapter.getContent() == null ? "" : chapter.getContent(),
                chapter.getStatus()
        );
    }

    public KnowledgeDocumentEntity loadDocumentUnchecked(UUID docId) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    public Map<String, Object> searchKnowledge(UUID ownerId, UUID bookId, RagDtos.RagSearchRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        Map<String, Object> body = new HashMap<>();
        body.put("owner_id", ownerId.toString());
        body.put("book_id", bookId.toString());
        body.put("query", req.query().trim());
        body.put("top_k", req.topK() != null ? Math.max(1, Math.min(req.topK(), 20)) : 5);
        if (req.maxChapterNo() != null) {
            body.put("max_chapter_no", req.maxChapterNo());
        }
        if (req.docType() != null && !req.docType().isBlank()) {
            body.put("doc_type", req.docType().trim().toUpperCase(Locale.ROOT));
        }
        body.put("rewrite_query", Boolean.TRUE.equals(req.rewriteQuery()));

        try {
            Map<String, Object> resp = agentRestClient.post()
                    .uri("/internal/v1/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return resp != null ? resp : Map.of("hits", List.of());
        } catch (Exception ex) {
            throw BizException.serviceUnavailable("RAG_SEARCH_FAILED", "混合检索服务暂时不可用: " + ex.getMessage());
        }
    }

    // --- Helpers ---

    private void insertChunkRow(KnowledgeDocumentEntity doc, int version, RagDtos.InternalChunkInput c) {
        DocumentChunkEntity row = new DocumentChunkEntity();
        row.setId(c.id() != null ? c.id() : UUID.randomUUID());
        row.setDocumentId(doc.getId());
        row.setBookId(doc.getBookId());
        row.setOwnerId(doc.getOwnerId());
        row.setDocVersion(version);
        row.setChunkLevel(c.chunkLevel() != null ? c.chunkLevel().toUpperCase(Locale.ROOT) : "CHILD");
        row.setParentChunkId(c.parentChunkId());
        row.setChunkIndex(c.chunkIndex() != null ? c.chunkIndex() : 0);
        row.setChapterNo(c.chapterNo() != null ? c.chapterNo() : doc.getChapterNo());
        row.setSceneTitle(c.sceneTitle() != null ? c.sceneTitle() : "");
        row.setContent(c.content() != null ? c.content() : "");
        row.setTokenCount(c.tokenCount() != null ? c.tokenCount() : 0);
        row.setMetadataJson(c.metadataJson() != null ? c.metadataJson() : "{}");
        chunkMapper.insert(row);
    }

    private void softDeleteAndEnqueue(KnowledgeDocumentEntity doc) {
        int nextVersion = (doc.getDocVersion() == null ? 1 : doc.getDocVersion()) + 1;
        doc.setDocVersion(nextVersion);
        doc.setStatus("DELETED");
        doc.setDeletedAt(OffsetDateTime.now());
        documentMapper.updateById(doc);

        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, doc.getId()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("document_id", doc.getId().toString());
        payload.put("book_id", doc.getBookId().toString());
        payload.put("owner_id", doc.getOwnerId().toString());
        payload.put("doc_version", nextVersion);
        payload.put("idempotency_key", "DOC_DELETE:" + doc.getId() + ":v" + nextVersion);

        outboxService.enqueue(
                "DOCUMENT",
                doc.getId(),
                "DOC_DELETE",
                RabbitMqTopologyConfig.RK_DELETE,
                "DOC_DELETE:" + doc.getId() + ":v" + nextVersion,
                payload
        );

        minioStorage.deleteQuietly(doc.getMinioBucket(), doc.getMinioObjectKey());
    }

    private void enqueueIngestEvent(KnowledgeDocumentEntity doc) {
        String idempotencyKey = "DOC_INGEST:" + doc.getId() + ":v" + doc.getDocVersion();
        Map<String, Object> payload = new HashMap<>();
        payload.put("document_id", doc.getId().toString());
        payload.put("book_id", doc.getBookId().toString());
        payload.put("owner_id", doc.getOwnerId().toString());
        payload.put("source_type", doc.getSourceType());
        payload.put("source_chapter_id", doc.getSourceChapterId() != null ? doc.getSourceChapterId().toString() : null);
        payload.put("chapter_no", doc.getChapterNo());
        payload.put("doc_type", doc.getDocType());
        payload.put("title", doc.getTitle());
        payload.put("file_name", doc.getFileName());
        payload.put("minio_bucket", doc.getMinioBucket());
        payload.put("minio_object_key", doc.getMinioObjectKey());
        payload.put("doc_version", doc.getDocVersion());
        payload.put("idempotency_key", idempotencyKey);

        outboxService.enqueue(
                "DOCUMENT",
                doc.getId(),
                "CHAPTER".equals(doc.getSourceType()) ? "CHAPTER_SYNC" : "DOC_INGEST",
                RabbitMqTopologyConfig.RK_INGEST,
                idempotencyKey,
                payload
        );
    }

    private void enqueueMemoryExtractEvent(UUID ownerId, UUID bookId, ChapterEntity chapter, int triggerSeq) {
        String idempotencyKey = "MEMORY_EXTRACT:" + chapter.getId() + ":v" + triggerSeq;
        Map<String, Object> payload = new HashMap<>();
        payload.put("book_id", bookId.toString());
        payload.put("owner_id", ownerId.toString());
        payload.put("chapter_id", chapter.getId().toString());
        payload.put("chapter_no", chapter.getChapterNo());
        payload.put("title", chapter.getTitle());
        payload.put("idempotency_key", idempotencyKey);

        outboxService.enqueue(
                "CHAPTER",
                chapter.getId(),
                "MEMORY_EXTRACT",
                RabbitMqTopologyConfig.RK_MEMORY,
                idempotencyKey,
                payload
        );
    }

    private KnowledgeDocumentEntity requireOwnedDocument(UUID ownerId, UUID bookId, UUID docId) {
        bookService.requireOwnedBook(ownerId, bookId);
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null || !bookId.equals(doc.getBookId())) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    private static String extractExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0) return "";
        return filename.substring(idx).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static String normalizeDocType(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_DOC_TYPES.contains(upper)) {
            throw BizException.badRequest("INVALID_DOC_TYPE", "不支持的文档类型: " + upper);
        }
        return upper;
    }

    public static RagDtos.KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity d) {
        return new RagDtos.KnowledgeDocumentResponse(
                d.getId(), d.getBookId(), d.getSourceType(), d.getSourceChapterId(),
                d.getChapterNo(), d.getDocType(), d.getTitle(), d.getFileName(),
                d.getContentType(), d.getFileSize(), d.getDocVersion(),
                d.getStatus(), d.getProgress(), d.getChunkCount(), d.getErrorMessage(),
                d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
