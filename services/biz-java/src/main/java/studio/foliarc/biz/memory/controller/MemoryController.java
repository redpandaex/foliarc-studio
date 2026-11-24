package studio.foliarc.biz.memory.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.memory.service.MemoryService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/v1/books/{bookId}/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final KnowledgeDocumentService documentService;

    public MemoryController(MemoryService memoryService, KnowledgeDocumentService documentService) {
        this.memoryService = memoryService;
        this.documentService = documentService;
    }

    @GetMapping
    public RagDtos.StructuredMemoryResponse getMemory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @RequestParam(required = false) Integer maxChapterNo
    ) {
        return memoryService.getStructuredMemory(principal.userId(), bookId, maxChapterNo);
    }

    @PostMapping("/relations")
    public RagDtos.CharacterRelationResponse upsertRelation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @Valid @RequestBody RagDtos.UpsertRelationRequest body
    ) {
        return memoryService.upsertRelation(principal.userId(), bookId, body);
    }

    @DeleteMapping("/relations/{relationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRelation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID relationId
    ) {
        memoryService.deleteRelation(principal.userId(), bookId, relationId);
    }

    @PostMapping("/timeline")
    public RagDtos.TimelineEventResponse upsertTimeline(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @Valid @RequestBody RagDtos.UpsertTimelineEventRequest body
    ) {
        return memoryService.upsertTimelineEvent(principal.userId(), bookId, body);
    }

    @DeleteMapping("/timeline/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeline(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID eventId
    ) {
        memoryService.deleteTimelineEvent(principal.userId(), bookId, eventId);
    }

    @PostMapping("/chapters/{chapterId}/extract")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> triggerExtract(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId
    ) {
        documentService.triggerMemoryExtractOnly(principal.userId(), bookId, chapterId);
        return Map.of("queued", true, "chapterId", chapterId);
    }
}
