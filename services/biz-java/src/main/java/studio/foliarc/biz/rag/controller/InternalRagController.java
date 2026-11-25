package studio.foliarc.biz.rag.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.memory.service.MemoryService;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

/**
 * Internal endpoints called by Python RabbitMQ RAG/Memory workers and Agent tools.
 * Secured via constant-time {@code X-Internal-Token} and tenant {@code X-Owner-Id} checks.
 */
@RestController
@RequestMapping("/internal/v1/rag")
public class InternalRagController {

    private final KnowledgeDocumentService documentService;
    private final MemoryService memoryService;
    private final StoryMemoryContextService storyMemoryContextService;
    private final OutboxService outboxService;
    private final byte[] expectedToken;

    public InternalRagController(
            KnowledgeDocumentService documentService,
            MemoryService memoryService,
            StoryMemoryContextService storyMemoryContextService,
            OutboxService outboxService,
            @Value("${foliarc.agent.internal-token}") String internalToken
    ) {
        this.documentService = documentService;
        this.memoryService = memoryService;
        this.storyMemoryContextService = storyMemoryContextService;
        this.outboxService = outboxService;
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/outbox/claim-idempotency")
    public RagDtos.InternalIdempotencyResponse claimIdempotency(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody RagDtos.InternalIdempotencyRequest body
    ) {
        verifyToken(token);
        boolean claimed = outboxService.claimConsumerIdempotency(body.idempotencyKey(), body.consumerName());
        return new RagDtos.InternalIdempotencyResponse(claimed);
    }

    @GetMapping("/documents/{docId}")
    public RagDtos.KnowledgeDocumentResponse getDocument(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID docId
    ) {
        verifyToken(token);
        return KnowledgeDocumentService.toResponse(documentService.loadDocumentUnchecked(docId));
    }

    @PostMapping("/documents/{docId}/status")
    public RagDtos.KnowledgeDocumentResponse updateStatus(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID docId,
            @Valid @RequestBody RagDtos.InternalStatusUpdateRequest body
    ) {
        verifyToken(token);
        return KnowledgeDocumentService.toResponse(documentService.updateIngestionStatus(docId, body));
    }

    @PostMapping("/documents/{docId}/chunks")
    public Map<String, Object> saveChunks(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID docId,
            @RequestBody RagDtos.InternalSaveChunksRequest body
    ) {
        verifyToken(token);
        int saved = documentService.saveDocumentChunks(docId, body);
        return Map.of("documentId", docId, "saved", saved);
    }

    @PostMapping("/books/{bookId}/parent-chunks")
    public List<RagDtos.InternalParentChunkResponse> loadParentChunks(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestBody List<UUID> parentIds
    ) {
        verifyToken(token);
        return documentService.loadParentChunks(parseOwner(ownerId), bookId, parentIds);
    }

    @GetMapping("/chapters/{chapterId}")
    public RagDtos.InternalChapterPayload loadChapter(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID chapterId
    ) {
        verifyToken(token);
        return documentService.loadChapterForWorker(chapterId);
    }

    @GetMapping("/books/{bookId}/memory")
    public RagDtos.StructuredMemoryResponse getMemory(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestParam(required = false) Integer maxChapterNo
    ) {
        verifyToken(token);
        return memoryService.getStructuredMemory(parseOwner(ownerId), bookId, maxChapterNo);
    }

    @PostMapping("/books/{bookId}/memory/apply")
    public RagDtos.ApplyExtractedMemoryResponse applyMemory(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestBody RagDtos.ApplyExtractedMemoryRequest body
    ) {
        verifyToken(token);
        return memoryService.applyExtractedMemory(parseOwner(ownerId), bookId, body);
    }

    @GetMapping("/books/{bookId}/accessible-docs")
    public RagDtos.InternalAccessibleDocsResponse accessibleDocs(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId
    ) {
        verifyToken(token);
        return documentService.resolveAccessibleDocScopes(parseOwner(ownerId), bookId);
    }

    @GetMapping("/books/{bookId}/summaries")
    public RagDtos.InternalBookSummariesPayload listSummaries(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID bookId
    ) {
        verifyToken(token);
        return storyMemoryContextService.loadBookSummariesForWorker(bookId);
    }

    @PostMapping("/books/{bookId}/summaries/chapter")
    public RagDtos.InternalSaveChapterSummaryResponse saveChapterSummary(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID bookId,
            @RequestBody RagDtos.InternalSaveChapterSummaryRequest body
    ) {
        verifyToken(token);
        return storyMemoryContextService.saveChapterSummary(bookId, body);
    }

    @PostMapping("/books/{bookId}/summaries/volume")
    public Map<String, Object> saveVolumeSummary(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID bookId,
            @RequestBody RagDtos.InternalSaveVolumeSummaryRequest body
    ) {
        verifyToken(token);
        storyMemoryContextService.saveVolumeSummary(bookId, body);
        return Map.of("saved", true, "volumeNo", body.volumeNo() != null ? body.volumeNo() : 1);
    }

    @PostMapping("/books/{bookId}/charter/auto")
    public Map<String, Object> saveAutoCharter(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID bookId,
            @RequestBody RagDtos.InternalSaveAutoCharterRequest body
    ) {
        verifyToken(token);
        boolean updated = storyMemoryContextService.saveAutoCharter(bookId, body);
        return Map.of("updated", updated);
    }

    private void verifyToken(String token) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            throw BizException.forbidden("INVALID_INTERNAL_TOKEN", "非法内部服务凭证");
        }
    }

    private static UUID parseOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw BizException.badRequest("MISSING_OWNER_ID", "必须提供 X-Owner-Id");
        }
        try {
            return UUID.fromString(ownerId.trim());
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("INVALID_OWNER_ID", "无效的 X-Owner-Id");
        }
    }
}
