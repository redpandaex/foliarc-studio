package studio.foliarc.admin.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.biz.billing.entity.GuardrailAuditLogEntity;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.run.entity.AgentCheckpointEntity;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.entity.AgentRunEventEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunEventMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;

@RestController
@RequestMapping("/api/v1/admin/traces")
public class AdminTraceController {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentCheckpointMapper checkpointMapper;
    private final GuardrailAuditLogMapper guardrailMapper;

    public AdminTraceController(AgentRunMapper runMapper, AgentRunEventMapper eventMapper,
                                AgentCheckpointMapper checkpointMapper, GuardrailAuditLogMapper guardrailMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.checkpointMapper = checkpointMapper;
        this.guardrailMapper = guardrailMapper;
    }

    @GetMapping("/runs")
    public List<RunSummary> listRuns(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String traceId,
                                     @RequestParam(defaultValue = "30") int limit) {
        String filter = AdminQuery.normalizeStatus(status);
        var query = Wrappers.<AgentRunEntity>lambdaQuery()
                .select(AgentRunEntity::getId, AgentRunEntity::getBookId, AgentRunEntity::getChapterId,
                        AgentRunEntity::getOwnerId, AgentRunEntity::getAction, AgentRunEntity::getStatus,
                        AgentRunEntity::getModelAlias, AgentRunEntity::getInputTokens, AgentRunEntity::getCachedTokens,
                        AgentRunEntity::getOutputTokens, AgentRunEntity::getErrorMessage, AgentRunEntity::getCreatedAt,
                        AgentRunEntity::getUpdatedAt)
                .eq(filter != null, AgentRunEntity::getStatus, filter)
                .orderByDesc(AgentRunEntity::getCreatedAt, AgentRunEntity::getId)
                .last(AdminQuery.limit(limit));
        if (traceId != null && !traceId.isBlank()) {
            List<UUID> runIds = eventMapper.selectList(Wrappers.<AgentRunEventEntity>lambdaQuery()
                            .select(AgentRunEventEntity::getRunId)
                            .eq(AgentRunEventEntity::getTraceId, traceId)
                            .groupBy(AgentRunEventEntity::getRunId))
                    .stream().map(AgentRunEventEntity::getRunId).toList();
            if (runIds.isEmpty()) {
                return List.of();
            }
            query.in(AgentRunEntity::getId, runIds);
        }
        return runMapper.selectList(query).stream().map(RunSummary::from).toList();
    }

    @GetMapping("/runs/{runId}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunDetail runDetail(@PathVariable UUID runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("RUN_NOT_FOUND", "Agent Run 不存在");
        }
        var events = eventMapper.selectList(Wrappers.<AgentRunEventEntity>lambdaQuery()
                .eq(AgentRunEventEntity::getRunId, runId)
                .orderByAsc(AgentRunEventEntity::getSeq, AgentRunEventEntity::getCreatedAt, AgentRunEventEntity::getId));
        var checkpoints = checkpointMapper.selectList(Wrappers.<AgentCheckpointEntity>lambdaQuery()
                .eq(AgentCheckpointEntity::getRunId, runId)
                .orderByAsc(AgentCheckpointEntity::getStepNo));
        var guardrailLogs = guardrailMapper.selectList(Wrappers.<GuardrailAuditLogEntity>lambdaQuery()
                .eq(GuardrailAuditLogEntity::getRunId, runId)
                .orderByAsc(GuardrailAuditLogEntity::getCreatedAt, GuardrailAuditLogEntity::getId));
        return new RunDetail(run, events, checkpoints, guardrailLogs);
    }

    @GetMapping("/guardrails")
    public List<GuardrailAuditLogEntity> listGuardrails(@RequestParam(defaultValue = "50") int limit) {
        return guardrailMapper.selectList(Wrappers.<GuardrailAuditLogEntity>lambdaQuery()
                .orderByDesc(GuardrailAuditLogEntity::getCreatedAt, GuardrailAuditLogEntity::getId)
                .last(AdminQuery.limit(limit)));
    }

    public record RunDetail(AgentRunEntity run, List<AgentRunEventEntity> events,
                            List<AgentCheckpointEntity> checkpoints, List<GuardrailAuditLogEntity> guardrailLogs) {}

    public record RunSummary(UUID id, UUID bookId, UUID chapterId, UUID userId, String action, String status,
                             String modelAlias, Integer promptTokens, Integer cachedTokens, Integer completionTokens,
                             String errorMessage, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static RunSummary from(AgentRunEntity run) {
            return new RunSummary(run.getId(), run.getBookId(), run.getChapterId(), run.getOwnerId(), run.getAction(),
                    run.getStatus(), run.getModelAlias(), run.getInputTokens(), run.getCachedTokens(), run.getOutputTokens(),
                    run.getErrorMessage(), run.getCreatedAt(), run.getUpdatedAt());
        }
    }
}
