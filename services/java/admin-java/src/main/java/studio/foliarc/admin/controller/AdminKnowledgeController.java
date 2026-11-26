package studio.foliarc.admin.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.admin.service.AdminReportingService;
import studio.foliarc.admin.service.AdminReportingService.DocumentGrantRow;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.service.UserAccountService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.entity.KnowledgeGrantEntity;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeController {

    private final KnowledgeDocumentService documentService;
    private final UserAccountService userAccountService;
    private final AdminAuditService auditService;
    private final AdminReportingService reportingService;

    public AdminKnowledgeController(KnowledgeDocumentService documentService, UserAccountService userAccountService,
                                    AdminAuditService auditService, AdminReportingService reportingService) {
        this.documentService = documentService;
        this.userAccountService = userAccountService;
        this.auditService = auditService;
        this.reportingService = reportingService;
    }

    @GetMapping("/documents")
    public List<RagDtos.LibraryDocumentResponse> listPlatformDocuments() {
        return documentService.listPlatformDocuments();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public RagDtos.LibraryDocumentResponse uploadPlatformDocument(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "WORLDBOOK") String docType,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String genreTags,
            @RequestParam(required = false) String genreCodes,
            @RequestParam(defaultValue = "true") boolean publicToAll
    ) {
        var document = documentService.uploadPlatformDocument(
                principal.userId(), file, docType, title, genreTags, genreCodes, publicToAll);
        auditService.record(principal, "KNOWLEDGE_UPLOAD", "DOCUMENT", document.id(), null, document);
        return document;
    }

    @DeleteMapping("/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deletePlatformDocument(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID docId) {
        var document = documentService.requirePlatformDocumentForUpdate(docId);
        var before = KnowledgeDocumentService.toResponse(document);
        documentService.deletePlatformDocument(document);
        auditService.record(principal, "KNOWLEDGE_DELETE", "DOCUMENT", docId,
                before, KnowledgeDocumentService.toResponse(document));
    }

    @GetMapping("/documents/{docId}/grants")
    public List<DocumentGrantRow> listGrants(@PathVariable UUID docId) {
        return reportingService.listDocumentGrants(docId);
    }

    @PostMapping("/documents/{docId}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> grantToAuthor(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID docId,
            @Valid @RequestBody RagDtos.GrantDocumentRequest body
    ) {
        if (body.granteeUserId() == null) {
            throw BizException.badRequest("MISSING_GRANTEE", "granteeUserId 不能为空");
        }
        userAccountService.requireUser(body.granteeUserId());
        var change = documentService.grantDocumentToUser(
                principal.userId(), docId, body.granteeUserId(), body.permission());
        Object after = body.note() == null || body.note().isBlank()
                ? change.after() : new GrantNote(change.after(), body.note().strip());
        auditService.record(principal, "KNOWLEDGE_GRANT", "DOCUMENT", docId, change.before(), after);
        return Map.of("granted", true, "documentId", docId, "granteeUserId", body.granteeUserId());
    }

    @DeleteMapping("/documents/{docId}/grants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revokeGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID docId,
            @PathVariable UUID userId
    ) {
        var before = documentService.revokeDocumentGrant(docId, userId);
        Object affectedGrant = before != null ? before : Map.of("granteeUserId", userId);
        auditService.record(principal, "KNOWLEDGE_REVOKE", "DOCUMENT", docId, affectedGrant, null);
    }

    @PostMapping("/debug-search")
    public Map<String, Object> debugSearch(@Valid @RequestBody DebugSearchRequest request) {
        return documentService.searchKnowledge(request.ownerId(), request.bookId(),
                new RagDtos.RagSearchRequest(request.query(), request.topK(), request.maxChapterNo(), null, false));
    }

    public record DebugSearchRequest(
            @NotNull UUID ownerId,
            @NotNull UUID bookId,
            @NotBlank @Size(max = 1000) String query,
            Integer topK,
            @Min(0) Integer maxChapterNo
    ) {}

    private record GrantNote(KnowledgeGrantEntity grant, String note) {}
}
