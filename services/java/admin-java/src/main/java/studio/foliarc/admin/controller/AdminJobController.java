package studio.foliarc.admin.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.admin.service.AdminReportingService;
import studio.foliarc.admin.service.AdminReportingService.RippleJobRow;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.outbox.entity.OutboxEventEntity;
import studio.foliarc.biz.outbox.mapper.OutboxEventMapper;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/v1/admin/jobs")
public class AdminJobController {

    private final OutboxEventMapper outboxMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final AdminReportingService reportingService;
    private final KnowledgeDocumentService documentService;
    private final AdminAuditService auditService;

    public AdminJobController(OutboxEventMapper outboxMapper, KnowledgeDocumentMapper documentMapper,
                              AdminReportingService reportingService, KnowledgeDocumentService documentService,
                              AdminAuditService auditService) {
        this.outboxMapper = outboxMapper;
        this.documentMapper = documentMapper;
        this.reportingService = reportingService;
        this.documentService = documentService;
        this.auditService = auditService;
    }

    @GetMapping("/outbox")
    public List<OutboxEventEntity> listOutbox(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "50") int limit) {
        String filter = AdminQuery.normalizeStatus(status);
        return outboxMapper.selectList(Wrappers.<OutboxEventEntity>lambdaQuery()
                .eq(filter != null, OutboxEventEntity::getStatus, filter)
                .orderByDesc(OutboxEventEntity::getCreatedAt, OutboxEventEntity::getId)
                .last(AdminQuery.limit(limit)));
    }

    @PostMapping("/outbox/{eventId}/retry")
    @Transactional
    public OutboxEventEntity retryOutbox(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID eventId) {
        var event = outboxMapper.selectOne(Wrappers.<OutboxEventEntity>lambdaQuery()
                .eq(OutboxEventEntity::getId, eventId)
                .last("FOR UPDATE"));
        if (event == null) {
            throw BizException.notFound("OUTBOX_EVENT_NOT_FOUND", "Outbox 事件不存在");
        }
        var before = OutboxState.from(event);
        OffsetDateTime nextRetry = OffsetDateTime.now();
        outboxMapper.update(null, Wrappers.<OutboxEventEntity>lambdaUpdate()
                .eq(OutboxEventEntity::getId, eventId)
                .set(OutboxEventEntity::getStatus, "PENDING")
                .set(OutboxEventEntity::getRetryCount, 0)
                .set(OutboxEventEntity::getLastError, null)
                .set(OutboxEventEntity::getPublishedAt, null)
                .set(OutboxEventEntity::getNextRetryAt, nextRetry));
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setLastError(null);
        event.setPublishedAt(null);
        event.setNextRetryAt(nextRetry);
        auditService.record(principal, "JOB_RETRY", "OUTBOX_EVENT", eventId, before, OutboxState.from(event));
        return event;
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentEntity> listDocuments(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "50") int limit) {
        String filter = AdminQuery.normalizeStatus(status);
        return documentMapper.selectList(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(filter != null, KnowledgeDocumentEntity::getStatus, filter)
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt, KnowledgeDocumentEntity::getId)
                .last(AdminQuery.limit(limit)));
    }

    @PostMapping("/documents/{docId}/retry")
    @Transactional
    public RagDtos.KnowledgeDocumentResponse retryDocument(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID docId
    ) {
        var document = documentService.requireActiveDocumentForUpdate(docId);
        var before = KnowledgeDocumentService.toResponse(document);
        var after = documentService.requeueDocument(document);
        auditService.record(principal, "DOC_REPROCESS", "DOCUMENT", docId, before, after);
        return after;
    }

    @GetMapping("/ripples")
    public List<RippleJobRow> listRipples(@RequestParam(defaultValue = "30") int limit) {
        return reportingService.recentRippleJobs(Math.max(0, limit));
    }

    private record OutboxState(String status, Integer retryCount, String lastError,
                               OffsetDateTime publishedAt, OffsetDateTime nextRetryAt) {
        static OutboxState from(OutboxEventEntity event) {
            return new OutboxState(event.getStatus(), event.getRetryCount(), event.getLastError(),
                    event.getPublishedAt(), event.getNextRetryAt());
        }
    }
}
