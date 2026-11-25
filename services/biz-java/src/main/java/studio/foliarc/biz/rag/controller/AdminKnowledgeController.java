package studio.foliarc.biz.rag.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

/**
 * Admin-only endpoints for Platform Public (GLOBAL) Knowledge Packs
 * and zero-copy author-specific grants (ASSIGNED).
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeController {

    private final KnowledgeDocumentService documentService;

    public AdminKnowledgeController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/documents")
    public List<RagDtos.LibraryDocumentResponse> listPlatformDocuments(
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        requireAdmin(principal);
        return documentService.listPlatformDocuments();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagDtos.LibraryDocumentResponse uploadPlatformDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "docType", required = false, defaultValue = "WORLDBOOK") String docType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "genreTags", required = false) String genreTags,
            @RequestParam(value = "genreCodes", required = false) String genreCodes,
            @RequestParam(value = "publicToAll", required = false, defaultValue = "true") boolean publicToAll
    ) {
        requireAdmin(principal);
        return documentService.uploadPlatformDocument(
                principal.userId(), file, docType, title, genreTags, genreCodes, publicToAll
        );
    }

    @PostMapping("/documents/{docId}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> grantToAuthor(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID docId,
            @RequestBody RagDtos.GrantDocumentRequest body
    ) {
        requireAdmin(principal);
        if (body == null || body.granteeUserId() == null) {
            throw BizException.badRequest("MISSING_GRANTEE", "granteeUserId 不能为空");
        }
        documentService.grantDocumentToUser(principal.userId(), docId, body.granteeUserId(), body.permission());
        return Map.of("granted", true, "documentId", docId, "granteeUserId", body.granteeUserId());
    }

    @DeleteMapping("/documents/{docId}/grants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID docId,
            @PathVariable UUID userId
    ) {
        requireAdmin(principal);
        documentService.revokeDocumentGrant(docId, userId);
    }

    private static void requireAdmin(AuthenticatedUser principal) {
        if (principal == null || !"ADMIN".equalsIgnoreCase(principal.role())) {
            throw BizException.forbidden("ADMIN_REQUIRED", "仅管理员可访问该后台接口");
        }
    }
}
