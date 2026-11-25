package studio.foliarc.biz.run.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.service.AgentRunEventService;
import studio.foliarc.biz.run.service.AgentRunService;

@RestController
@RequestMapping("/internal/v1/runs")
public class InternalRunController {

    private final AgentRunService runService;
    private final AgentRunEventService eventService;
    private final BillingService billingService;
    private final byte[] expectedTokenBytes;

    public InternalRunController(
            AgentRunService runService,
            AgentRunEventService eventService,
            BillingService billingService,
            @Value("${foliarc.agent.internal-token}") String internalToken
    ) {
        this.runService = runService;
        this.eventService = eventService;
        this.billingService = billingService;
        this.expectedTokenBytes = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{runId}/state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateState(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID runId,
            @RequestBody RunDtos.InternalStateUpdateRequest body
    ) {
        verifyToken(token);
        runService.updateInternalState(runId, body);
    }

    @PostMapping("/{runId}/checkpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public RunDtos.CheckpointResponse saveCheckpoint(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID runId,
            @RequestBody RunDtos.InternalSaveCheckpointRequest body
    ) {
        verifyToken(token);
        return runService.saveCheckpoint(runId, body);
    }

    @GetMapping("/{runId}/checkpoint/latest")
    public Map<String, Object> latestCheckpoint(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID runId
    ) {
        verifyToken(token);
        RunDtos.CheckpointResponse latest = runService.getLatestCheckpoint(runId);
        if (latest == null) {
            return Map.of("found", false);
        }
        return Map.of("found", true, "checkpoint", latest);
    }

    @PostMapping("/{runId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    public void saveEvents(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) RunDtos.InternalSaveRunEventsRequest body
    ) {
        verifyToken(token);
        eventService.saveEvents(runId, body == null ? null : body.events());
    }

    @PostMapping("/guardrails/audit")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordGuardrailAudit(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody BillingDtos.InternalGuardrailAuditRequest body
    ) {
        verifyToken(token);
        billingService.recordGuardrailAudit(body);
    }

    private void verifyToken(String token) {
        if (expectedTokenBytes.length == 0 || token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedTokenBytes)) {
            throw BizException.forbidden("INVALID_INTERNAL_TOKEN", "非法内部服务凭证");
        }
    }
}
