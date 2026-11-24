package studio.foliarc.biz.run.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentCheckpointEntity;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;

@Service
public class AgentRunService {

    private static final Set<String> VALID_ACTIONS = Set.of("CONTINUE", "REWRITE", "OUTLINE");
    private static final Set<String> VALID_MODES = Set.of("AGENT", "DIRECT", "MULTI_AGENT");
    private static final Duration STREAM_TTL = Duration.ofHours(2);

    private final AgentRunMapper runMapper;
    private final AgentCheckpointMapper checkpointMapper;
    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final WebClient agentWebClient;
    private final StringRedisTemplate redis;
    private final RedisRateLimiterService rateLimiter;
    private final BillingService billingService;
    private final CircuitBreaker agentCircuitBreaker;
    private final Bulkhead agentBulkhead;

    public AgentRunService(
            AgentRunMapper runMapper,
            AgentCheckpointMapper checkpointMapper,
            BookService bookService,
            ChapterMapper chapterMapper,
            WebClient agentWebClient,
            StringRedisTemplate redis,
            RedisRateLimiterService rateLimiter,
            BillingService billingService,
            CircuitBreaker agentCircuitBreaker,
            Bulkhead agentBulkhead
    ) {
        this.runMapper = runMapper;
        this.checkpointMapper = checkpointMapper;
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.agentWebClient = agentWebClient;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.billingService = billingService;
        this.agentCircuitBreaker = agentCircuitBreaker;
        this.agentBulkhead = agentBulkhead;
    }

    public RunDtos.RunResponse startRun(UUID ownerId, RunDtos.StartRunRequest req) {
        BookEntity book = bookService.requireOwnedBook(ownerId, req.bookId());

        ChapterEntity chapter = null;
        if (req.chapterId() != null) {
            chapter = chapterMapper.selectById(req.chapterId());
            if (chapter == null || !book.getId().equals(chapter.getBookId())) {
                throw BizException.notFound("CHAPTER_NOT_FOUND", "目标章节不存在");
            }
        }

        String mode = (req.mode() == null || req.mode().isBlank())
                ? "AGENT"
                : req.mode().trim().toUpperCase();
        if (!VALID_MODES.contains(mode)) {
            throw BizException.badRequest("INVALID_RUN_MODE", "不支持的运行模式: " + mode);
        }

        String action = (req.action() == null || req.action().isBlank())
                ? "CONTINUE"
                : req.action().trim().toUpperCase();
        if (!VALID_ACTIONS.contains(action)) {
            throw BizException.badRequest("INVALID_RUN_ACTION", "不支持的生成动作: " + action);
        }

        String modelAlias = (req.modelAlias() == null || req.modelAlias().isBlank())
                ? "writer-main"
                : req.modelAlias().trim();
        String instruction = req.instruction() == null ? "" : req.instruction().trim();
        String chapterContext = req.chapterContext() != null
                ? req.chapterContext()
                : (chapter != null && chapter.getContent() != null ? chapter.getContent() : "");
        boolean requireOutlineApproval = req.requireOutlineApproval() == null
                ? "MULTI_AGENT".equals(mode)
                : Boolean.TRUE.equals(req.requireOutlineApproval());

        // Phase 5 Gateway Guard 1: Atomic Redis + Lua RPM & TPM Dual-Dimension Rate Limit
        long estTokens = ModelPricing.estimatePromptTokens(mode, chapterContext, instruction);
        rateLimiter.acquireOrThrow(ownerId, estTokens);

        AgentRunEntity run = new AgentRunEntity();
        run.setId(UUID.randomUUID());
        run.setBookId(book.getId());
        run.setChapterId(chapter != null ? chapter.getId() : null);
        run.setOwnerId(ownerId);
        run.setMode(mode);
        run.setAction(action);
        run.setModelAlias(modelAlias);
        run.setInstruction(instruction);
        run.setChapterContext(chapterContext);
        run.setStatus("QUEUED");
        run.setAccumulatedText("");
        run.setLastSeq(0);
        run.setInputTokens(0);
        run.setCachedTokens(0);
        run.setOutputTokens(0);
        run.setRevisionCount(0);
        run.setFrozenMicros(0L);
        run.setSettledMicros(0L);
        run.setBillingStatus("NONE");
        runMapper.insert(run);

        // Phase 5 Gateway Guard 2: Distributed Two-Phase Pre-Freeze (Balance + Monthly Budget Hard-Stop)
        try {
            long frozen = billingService.freezeForRun(ownerId, run.getId(), mode, modelAlias);
            run.setFrozenMicros(frozen);
            run.setBillingStatus("FROZEN");
            runMapper.updateById(run);
        } catch (RuntimeException ex) {
            run.setStatus("FAILED");
            run.setErrorMessage(ex.getMessage());
            runMapper.updateById(run);
            throw ex;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.getId().toString());
        body.put("owner_id", ownerId.toString());
        body.put("book_id", book.getId().toString());
        body.put("chapter_no", chapter != null ? chapter.getChapterNo() : null);
        body.put("mode", mode);
        body.put("action", action);
        body.put("model_alias", modelAlias);
        body.put("book_title", book.getTitle());
        body.put("genre", book.getGenre());
        body.put("synopsis", book.getSynopsis());
        body.put("chapter_title", chapter != null ? chapter.getTitle() : "");
        body.put("chapter_context", chapterContext);
        body.put("instruction", instruction);
        body.put("require_outline_approval", requireOutlineApproval);

        dispatchToAgent("/internal/v1/runs/start", body, run, true);
        return toResponse(run);
    }

    public RunDtos.RunResponse getRun(UUID ownerId, UUID runId) {
        return toResponse(requireOwnedRun(ownerId, runId));
    }

    public List<RunDtos.RunResponse> listRuns(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getOwnerId, ownerId)
                .eq(AgentRunEntity::getBookId, bookId)
                .orderByDesc(AgentRunEntity::getCreatedAt)
                .last("LIMIT 20");
        if (chapterId != null) {
            query.eq(AgentRunEntity::getChapterId, chapterId);
        }
        return runMapper.selectList(query).stream().map(AgentRunService::toResponse).toList();
    }

    public List<RunDtos.CheckpointResponse> listCheckpoints(UUID ownerId, UUID runId) {
        requireOwnedRun(ownerId, runId);
        var query = new LambdaQueryWrapper<AgentCheckpointEntity>()
                .eq(AgentCheckpointEntity::getRunId, runId)
                .orderByAsc(AgentCheckpointEntity::getStepNo);
        return checkpointMapper.selectList(query).stream().map(AgentRunService::toCheckpoint).toList();
    }

    public AgentRunEntity requireOwnedRun(UUID ownerId, UUID runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("RUN_NOT_FOUND", "流式运行任务不存在");
        }
        if (!ownerId.equals(run.getOwnerId())) {
            throw BizException.forbidden("RUN_ACCESS_DENIED", "无权访问该任务");
        }
        return run;
    }

    public RunDtos.RunResponse pauseRun(UUID ownerId, UUID runId) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if ("PAUSED".equals(run.getStatus())) {
            return toResponse(run);
        }
        if (!"QUEUED".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())) {
            throw BizException.conflict("RUN_NOT_PAUSABLE", "当前任务状态不支持暂停: " + run.getStatus());
        }

        redis.opsForValue().set(RedisStreamSseBridge.controlKey(runId), "PAUSE", STREAM_TTL);
        signalAgentControl(runId, "PAUSE");
        return toResponse(run);
    }

    public RunDtos.RunResponse resumeRun(UUID ownerId, UUID runId, RunDtos.ResumeRunRequest req) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if (!"PAUSED".equals(run.getStatus())) {
            throw BizException.conflict("RUN_NOT_PAUSED", "只有已暂停的任务才能继续生成，当前状态: " + run.getStatus());
        }
        if (req != null && req.revisedInstruction() != null && !req.revisedInstruction().isBlank()) {
            run.setInstruction(req.revisedInstruction().trim());
        }
        return dispatchResumeInternal(ownerId, run, null);
    }

    public RunDtos.RunResponse approveRun(UUID ownerId, UUID runId, RunDtos.ApproveRunRequest req) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if (!"PAUSED".equals(run.getStatus())) {
            throw BizException.conflict("RUN_NOT_AWAITING_APPROVAL", "当前任务未处于等待人工审批状态: " + run.getStatus());
        }
        boolean approved = req == null || req.approved() == null || Boolean.TRUE.equals(req.approved());
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approved", approved);
        approval.put("edited_outline", req != null && req.editedOutline() != null ? req.editedOutline().trim() : "");
        approval.put("feedback", req != null && req.feedback() != null ? req.feedback().trim() : "");

        return dispatchResumeInternal(ownerId, run, approval);
    }

    private RunDtos.RunResponse dispatchResumeInternal(
            UUID ownerId,
            AgentRunEntity run,
            Map<String, Object> approvalPayload
    ) {
        BookEntity book = bookService.requireOwnedBook(ownerId, run.getBookId());
        ChapterEntity chapter = run.getChapterId() != null ? chapterMapper.selectById(run.getChapterId()) : null;

        run.setStatus("RUNNING");
        run.setErrorMessage(null);
        run.setInterruptType(null);
        run.setInterruptPayloadJson(null);
        runMapper.updateById(run);

        redis.delete(RedisStreamSseBridge.controlKey(run.getId()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.getId().toString());
        body.put("owner_id", ownerId.toString());
        body.put("book_id", book.getId().toString());
        body.put("chapter_no", chapter != null ? chapter.getChapterNo() : null);
        body.put("mode", run.getMode() == null ? "AGENT" : run.getMode());
        body.put("action", run.getAction());
        body.put("model_alias", run.getModelAlias());
        body.put("book_title", book.getTitle());
        body.put("genre", book.getGenre());
        body.put("synopsis", book.getSynopsis());
        body.put("chapter_title", chapter != null ? chapter.getTitle() : "");
        body.put("chapter_context", run.getChapterContext() == null ? "" : run.getChapterContext());
        body.put("instruction", run.getInstruction() == null ? "" : run.getInstruction());
        body.put("accumulated_text", run.getAccumulatedText() == null ? "" : run.getAccumulatedText());
        body.put("last_seq", run.getLastSeq() == null ? 0 : run.getLastSeq());
        body.put("prior_input_tokens", run.getInputTokens() == null ? 0 : run.getInputTokens());
        body.put("prior_cached_tokens", run.getCachedTokens() == null ? 0 : run.getCachedTokens());
        body.put("prior_output_tokens", run.getOutputTokens() == null ? 0 : run.getOutputTokens());
        if (approvalPayload != null) {
            body.put("approval_payload", approvalPayload);
        }

        dispatchToAgent("/internal/v1/runs/" + run.getId() + "/resume", body, run, false);
        return toResponse(run);
    }

    public RunDtos.RunResponse cancelRun(UUID ownerId, UUID runId) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if ("COMPLETED".equals(run.getStatus()) || "CANCELLED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            return toResponse(run);
        }

        if ("PAUSED".equals(run.getStatus())) {
            int nextSeq = (run.getLastSeq() == null ? 0 : run.getLastSeq()) + 1;
            RecordId recordId = appendCancelledEventToStream(runId, nextSeq, run.getAccumulatedText());
            run.setStatus("CANCELLED");
            run.setInterruptType(null);
            run.setInterruptPayloadJson(null);
            run.setLastSeq(nextSeq);
            if (recordId != null) {
                run.setLastEventId(recordId.getValue());
            }
            settleRunIfFrozen(run, 0, "CANCELLED");
            runMapper.updateById(run);
            return toResponse(run);
        }

        redis.opsForValue().set(RedisStreamSseBridge.controlKey(runId), "CANCEL", STREAM_TTL);
        signalAgentControl(runId, "CANCEL");
        return toResponse(run);
    }

    public void updateInternalState(UUID runId, RunDtos.InternalStateUpdateRequest req) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        if (req.status() != null && !req.status().isBlank()) {
            run.setStatus(req.status());
        }
        if (req.accumulatedText() != null) {
            run.setAccumulatedText(req.accumulatedText());
        }
        if (req.lastSeq() != null) {
            run.setLastSeq(req.lastSeq());
        }
        if (req.lastEventId() != null) {
            run.setLastEventId(req.lastEventId());
        }
        if (req.inputTokens() != null) {
            run.setInputTokens(req.inputTokens());
        }
        if (req.cachedTokens() != null) {
            run.setCachedTokens(req.cachedTokens());
        }
        if (req.outputTokens() != null) {
            run.setOutputTokens(req.outputTokens());
        }
        if (req.currentNode() != null) {
            run.setCurrentNode(req.currentNode());
        }
        if (req.interruptType() != null) {
            run.setInterruptType(req.interruptType().isBlank() ? null : req.interruptType());
        }
        if (req.interruptPayloadJson() != null) {
            run.setInterruptPayloadJson(req.interruptPayloadJson().isBlank() ? null : req.interruptPayloadJson());
        }
        if (req.routeIntent() != null) {
            run.setRouteIntent(req.routeIntent());
        }
        if (req.revisionCount() != null) {
            run.setRevisionCount(req.revisionCount());
        }
        run.setErrorMessage(req.errorMessage());

        // Phase 5: Idempotent Phase-2 Settlement on terminal run states (COMPLETED / CANCELLED / FAILED)
        if ("COMPLETED".equals(run.getStatus())
                || "CANCELLED".equals(run.getStatus())
                || "FAILED".equals(run.getStatus())) {
            int sandboxCalls = req.sandboxCalls() == null ? 0 : Math.max(0, req.sandboxCalls());
            settleRunIfFrozen(run, sandboxCalls, run.getStatus());
            int totalTok = (run.getInputTokens() == null ? 0 : run.getInputTokens())
                    + (run.getOutputTokens() == null ? 0 : run.getOutputTokens());
            rateLimiter.recordAdditionalTokens(run.getOwnerId(), totalTok);
        }
        runMapper.updateById(run);
    }

    @Transactional
    public RunDtos.CheckpointResponse saveCheckpoint(UUID runId, RunDtos.InternalSaveCheckpointRequest req) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("RUN_NOT_FOUND", "流式运行任务不存在");
        }
        int stepNo = req.stepNo() != null ? req.stepNo() : 1;
        AgentCheckpointEntity existing = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentCheckpointEntity>()
                        .eq(AgentCheckpointEntity::getRunId, runId)
                        .eq(AgentCheckpointEntity::getStepNo, stepNo)
        );
        if (existing != null) {
            existing.setNodeName(req.nodeName() != null ? req.nodeName() : existing.getNodeName());
            existing.setStatus(req.status() != null ? req.status() : "COMPLETED");
            existing.setStateJson(req.stateJson() != null ? req.stateJson() : "{}");
            checkpointMapper.updateById(existing);
            return toCheckpoint(existing);
        }

        AgentCheckpointEntity entity = new AgentCheckpointEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setStepNo(stepNo);
        entity.setNodeName(req.nodeName() != null ? req.nodeName() : "UNKNOWN");
        entity.setStatus(req.status() != null ? req.status() : "COMPLETED");
        entity.setStateJson(req.stateJson() != null ? req.stateJson() : "{}");
        checkpointMapper.insert(entity);
        return toCheckpoint(entity);
    }

    public RunDtos.CheckpointResponse getLatestCheckpoint(UUID runId) {
        AgentCheckpointEntity latest = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentCheckpointEntity>()
                        .eq(AgentCheckpointEntity::getRunId, runId)
                        .orderByDesc(AgentCheckpointEntity::getStepNo)
                        .last("LIMIT 1")
        );
        return latest == null ? null : toCheckpoint(latest);
    }

    private void settleRunIfFrozen(AgentRunEntity run, int sandboxCalls, String reason) {
        if (!"FROZEN".equals(run.getBillingStatus())) {
            return;
        }
        long frozen = run.getFrozenMicros() == null ? 0L : run.getFrozenMicros();
        long settled = billingService.settleRun(
                run.getOwnerId(),
                run.getId(),
                frozen,
                run.getModelAlias(),
                run.getInputTokens() == null ? 0 : run.getInputTokens(),
                run.getCachedTokens() == null ? 0 : run.getCachedTokens(),
                run.getOutputTokens() == null ? 0 : run.getOutputTokens(),
                sandboxCalls,
                reason
        );
        run.setSettledMicros(settled);
        run.setBillingStatus(settled == 0L ? "RELEASED" : "SETTLED");
    }

    private void dispatchToAgent(String path, Map<String, Object> body, AgentRunEntity run, boolean releaseOnFailure) {
        try {
            Runnable call = () -> agentWebClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));

            Runnable decorated = Bulkhead.decorateRunnable(
                    agentBulkhead,
                    CircuitBreaker.decorateRunnable(agentCircuitBreaker, call)
            );
            decorated.run();
        } catch (BulkheadFullException ex) {
            failAndRelease(run, releaseOnFailure, "网关并发舱壁已满，请稍后重试");
            throw BizException.tooManyRequests("GATEWAY_BULKHEAD_FULL", "当前 AI 生成并发已达上限，请稍后再试");
        } catch (CallNotPermittedException ex) {
            failAndRelease(run, releaseOnFailure, "Agent 引擎熔断器已打开，触发快速降级");
            throw BizException.serviceUnavailable("AGENT_CIRCUIT_OPEN", "AI 引擎短时波动已触发网关熔断保护，请 15 秒后重试");
        } catch (Exception ex) {
            failAndRelease(run, releaseOnFailure, "无法连接 Python Agent 引擎: " + ex.getMessage());
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE", "Python Agent 服务暂不可用，请确认 agent-py 已启动");
        }
    }

    private void failAndRelease(AgentRunEntity run, boolean releaseOnFailure, String errMsg) {
        run.setStatus("FAILED");
        run.setErrorMessage(errMsg);
        if (releaseOnFailure) {
            settleRunIfFrozen(run, 0, "DISPATCH_FAILED");
        }
        runMapper.updateById(run);
    }

    private void signalAgentControl(UUID runId, String signal) {
        try {
            agentWebClient.post()
                    .uri("/internal/v1/runs/{runId}/control", runId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("signal", signal))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
        } catch (Exception ignored) {
            // Redis control key is already set; Python runner checks it as a fallback.
        }
    }

    private RecordId appendCancelledEventToStream(UUID runId, int seq, String accumulatedText) {
        int chars = accumulatedText == null ? 0 : accumulatedText.length();
        String key = RedisStreamSseBridge.streamKey(runId);
        Map<String, String> fields = Map.of(
                "type", "done",
                "seq", String.valueOf(seq),
                "run_id", runId.toString(),
                "trace_id", "",
                "agent", "writer",
                "timestamp", Instant.now().toString(),
                "payload", "{\"status\":\"cancelled\",\"accumulated_chars\":" + chars + "}"
        );
        RecordId id = redis.opsForStream().add(key, fields);
        redis.expire(key, STREAM_TTL);
        return id;
    }

    private static RunDtos.RunResponse toResponse(AgentRunEntity r) {
        return new RunDtos.RunResponse(
                r.getId(),
                r.getBookId(),
                r.getChapterId(),
                r.getMode() == null ? "AGENT" : r.getMode(),
                r.getAction(),
                r.getModelAlias(),
                r.getInstruction(),
                r.getStatus(),
                r.getAccumulatedText() == null ? "" : r.getAccumulatedText(),
                r.getLastSeq() == null ? 0 : r.getLastSeq(),
                r.getLastEventId(),
                r.getInputTokens() == null ? 0 : r.getInputTokens(),
                r.getCachedTokens() == null ? 0 : r.getCachedTokens(),
                r.getOutputTokens() == null ? 0 : r.getOutputTokens(),
                r.getErrorMessage(),
                r.getCurrentNode(),
                r.getInterruptType(),
                r.getInterruptPayloadJson(),
                r.getRouteIntent(),
                r.getRevisionCount() == null ? 0 : r.getRevisionCount(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private static RunDtos.CheckpointResponse toCheckpoint(AgentCheckpointEntity c) {
        return new RunDtos.CheckpointResponse(
                c.getId(),
                c.getRunId(),
                c.getStepNo() == null ? 0 : c.getStepNo(),
                c.getNodeName(),
                c.getStatus(),
                c.getStateJson(),
                c.getCreatedAt()
        );
    }
}
