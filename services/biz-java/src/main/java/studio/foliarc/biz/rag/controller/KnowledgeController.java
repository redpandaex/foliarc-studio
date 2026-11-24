package studio.foliarc.biz.rag.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/v1/books/{bookId}")
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;

    public KnowledgeController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/documents")
    public List<RagDtos.KnowledgeDocumentResponse> listDocuments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId
    ) {
        return documentService.listDocuments(principal.userId(), bookId);
    }

    @GetMapping("/documents/{docId}")
    public RagDtos.KnowledgeDocumentResponse getDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID docId
    ) {
        return documentService.getDocument(principal.userId(), bookId, docId);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagDtos.KnowledgeDocumentResponse uploadDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "docType", required = false, defaultValue = "WORLDBOOK") String docType,
            @RequestParam(value = "chapterNo", required = false, defaultValue = "0") Integer chapterNo,
            @RequestParam(value = "title", required = false) String title
    ) {
        return documentService.uploadDocument(principal.userId(), bookId, file, docType, chapterNo, title);
    }

    @PostMapping("/documents/{docId}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagDtos.KnowledgeDocumentResponse reprocess(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID docId
    ) {
        return documentService.reprocessDocument(principal.userId(), bookId, docId);
    }

    @DeleteMapping("/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID docId
    ) {
        documentService.deleteDocument(principal.userId(), bookId, docId);
    }

    @PostMapping("/chapters/{chapterId}/sync-rag")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagDtos.SyncChapterResponse syncChapter(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId,
            @RequestBody(required = false) RagDtos.SyncChapterRequest body
    ) {
        boolean extractMemory = body == null || !Boolean.FALSE.equals(body.extractMemory());
        return documentService.syncChapter(principal.userId(), bookId, chapterId, extractMemory);
    }

    @PostMapping("/knowledge/search")
    public Map<String, Object> searchKnowledge(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @Valid @RequestBody RagDtos.RagSearchRequest body
    ) {
        return documentService.searchKnowledge(principal.userId(), bookId, body);
    }
}
