package studio.inkflow.biz.run.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.run.dto.RunDtos;
import studio.inkflow.biz.run.service.AgentRunService;

@RestController
@RequestMapping("/internal/v1/runs")
public class InternalRunController {

    private final AgentRunService runService;
    private final byte[] expectedTokenBytes;

    public InternalRunController(
            AgentRunService runService,
            @Value("${inkflow.agent.internal-token}") String internalToken
    ) {
        this.runService = runService;
        this.expectedTokenBytes = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{runId}/state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateState(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID runId,
            @RequestBody RunDtos.InternalStateUpdateRequest body
    ) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedTokenBytes)) {
            throw BizException.forbidden("INVALID_INTERNAL_TOKEN", "非法内部服务凭证");
        }
        runService.updateInternalState(runId, body);
    }
}
