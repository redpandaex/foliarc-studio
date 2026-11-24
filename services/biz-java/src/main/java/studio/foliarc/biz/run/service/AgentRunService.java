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

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentRunMapper;

@Service
public class AgentRunService {

    private static final Set<String> VALID_ACTIONS = Set.of("CONTINUE", "REWRITE", "OUTLINE");
    private static final Set<String> VALID_MODES = Set.of("AGENT", "DIRECT");
    private static final Duration STREAM_TTL = Duration.ofHours(2);

    private final AgentRunMapper runMapper;
    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final WebClient agentWebClient;
    private final StringRedisTemplate redis;

    public AgentRunService(
            AgentRunMapper runMapper,
            BookService bookService,
            ChapterMapper chapterMapper,
            WebClient agentWebClient,
            StringRedisTemplate redis
    ) {
        this.runMapper = runMapper;
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.agentWebClient = agentWebClient;
        this.redis = redis;
    }

    @Transactional
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
        runMapper.insert(run);

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

        dispatchToAgent("/internal/v1/runs/start", body, run);
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

    @Transactional
    public RunDtos.RunResponse resumeRun(UUID ownerId, UUID runId, RunDtos.ResumeRunRequest req) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if (!"PAUSED".equals(run.getStatus())) {
            throw BizException.conflict("RUN_NOT_PAUSED", "只有已暂停的任务才能继续生成，当前状态: " + run.getStatus());
        }

        BookEntity book = bookService.requireOwnedBook(ownerId, run.getBookId());
        ChapterEntity chapter = run.getChapterId() != null ? chapterMapper.selectById(run.getChapterId()) : null;

        if (req != null && req.revisedInstruction() != null && !req.revisedInstruction().isBlank()) {
            run.setInstruction(req.revisedInstruction().trim());
        }
        run.setStatus("RUNNING");
        run.setErrorMessage(null);
        runMapper.updateById(run);

        redis.delete(RedisStreamSseBridge.controlKey(runId));

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

        dispatchToAgent("/internal/v1/runs/" + runId + "/resume", body, run);
        return toResponse(run);
    }

    @Transactional
    public RunDtos.RunResponse cancelRun(UUID ownerId, UUID runId) {
        AgentRunEntity run = requireOwnedRun(ownerId, runId);
        if ("COMPLETED".equals(run.getStatus()) || "CANCELLED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            return toResponse(run);
        }

        if ("PAUSED".equals(run.getStatus())) {
            int nextSeq = (run.getLastSeq() == null ? 0 : run.getLastSeq()) + 1;
            RecordId recordId = appendCancelledEventToStream(runId, nextSeq, run.getAccumulatedText());
            run.setStatus("CANCELLED");
            run.setLastSeq(nextSeq);
            if (recordId != null) {
                run.setLastEventId(recordId.getValue());
            }
            runMapper.updateById(run);
            return toResponse(run);
        }

        redis.opsForValue().set(RedisStreamSseBridge.controlKey(runId), "CANCEL", STREAM_TTL);
        signalAgentControl(runId, "CANCEL");
        return toResponse(run);
    }

    @Transactional
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
        run.setErrorMessage(req.errorMessage());
        runMapper.updateById(run);
    }

    private void dispatchToAgent(String path, Map<String, Object> body, AgentRunEntity run) {
        try {
            agentWebClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setErrorMessage("无法连接 Python Agent 引擎: " + ex.getMessage());
            runMapper.updateById(run);
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE", "Python Agent 服务暂不可用，请确认 agent-py 已启动");
        }
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
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
