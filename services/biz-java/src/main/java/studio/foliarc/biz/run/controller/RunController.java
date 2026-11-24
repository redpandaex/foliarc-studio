package studio.foliarc.biz.run.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.service.AgentRunService;
import studio.foliarc.biz.run.service.RedisStreamSseBridge;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final AgentRunService runService;
    private final RedisStreamSseBridge sseBridge;

    public RunController(AgentRunService runService, RedisStreamSseBridge sseBridge) {
        this.runService = runService;
        this.sseBridge = sseBridge;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunDtos.RunResponse startRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody RunDtos.StartRunRequest body
    ) {
        return runService.startRun(actor.userId(), body);
    }

    @GetMapping
    public List<RunDtos.RunResponse> listRuns(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam UUID bookId,
            @RequestParam(required = false) UUID chapterId
    ) {
        return runService.listRuns(actor.userId(), bookId, chapterId);
    }

    @GetMapping("/{runId}")
    public RunDtos.RunResponse getRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId
    ) {
        return runService.getRun(actor.userId(), runId);
    }

    @GetMapping("/{runId}/checkpoints")
    public List<RunDtos.CheckpointResponse> listCheckpoints(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId
    ) {
        return runService.listCheckpoints(actor.userId(), runId);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            HttpServletResponse response
    ) {
        runService.requireOwnedRun(actor.userId(), runId);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        String cursor = (lastEventIdHeader != null && !lastEventIdHeader.isBlank())
                ? lastEventIdHeader
                : lastEventIdParam;
        return sseBridge.streamRun(runId, cursor, () -> runService.getRunStatus(runId));
    }

    @PostMapping("/{runId}/pause")
    public RunDtos.RunResponse pauseRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId
    ) {
        return runService.pauseRun(actor.userId(), runId);
    }

    @PostMapping("/{runId}/resume")
    public RunDtos.RunResponse resumeRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) RunDtos.ResumeRunRequest body
    ) {
        return runService.resumeRun(actor.userId(), runId, body);
    }

    @PostMapping("/{runId}/approve")
    public RunDtos.RunResponse approveRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) RunDtos.ApproveRunRequest body
    ) {
        return runService.approveRun(actor.userId(), runId, body);
    }

    @PostMapping("/{runId}/cancel")
    public RunDtos.RunResponse cancelRun(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID runId
    ) {
        return runService.cancelRun(actor.userId(), runId);
    }
}
