package studio.foliarc.biz.rag.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

/**
 * Writer-facing Personal Reference Library & Per-Book Mounting endpoints.
 * Exposes USER personal docs, read-only ASSIGNED grants, and enabled GLOBAL docs cleanly.
 */
@RestController
@RequestMapping("/api/v1")
public class LibraryController {

    private final KnowledgeDocumentService documentService;

    public LibraryController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/library/documents")
    public List<RagDtos.LibraryDocumentResponse> listAccessibleLibrary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(value = "bookId", required = false) UUID bookId
    ) {
        return documentService.listAccessibleLibrary(principal.userId(), bookId);
    }

    @PostMapping(value = "/library/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagDtos.LibraryDocumentResponse uploadPersonalDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "docType", required = false, defaultValue = "WORLDBOOK") String docType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "genreTags", required = false) String genreTags
    ) {
        return documentService.uploadPersonalDocument(principal.userId(), file, docType, title, genreTags);
    }

    @DeleteMapping("/library/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePersonalDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID docId
    ) {
        documentService.deletePersonalDocument(principal.userId(), docId);
    }

    @PutMapping("/books/{bookId}/library/{docId}/binding")
    public RagDtos.LibraryDocumentResponse updateBookBinding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID docId,
            @RequestBody RagDtos.UpdateBookBindingRequest body
    ) {
        return documentService.setBookBinding(principal.userId(), bookId, docId, body.enabled());
    }
}
