package studio.foliarc.admin.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;

@RestController
@RequestMapping("/api/v1/admin/evals")
public class AdminEvalController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient agentRestClient;
    private final AdminAuditService auditService;

    public AdminEvalController(RestClient agentRestClient, AdminAuditService auditService) {
        this.agentRestClient = agentRestClient;
        this.auditService = auditService;
    }

    @PostMapping("/run")
    public Map<String, Object> runGoldenEval(@AuthenticationPrincipal AuthenticatedUser principal) {
        Map<String, Object> report = agentRestClient.post()
                .uri("/internal/v1/evals/run")
                .retrieve()
                .body(MAP_TYPE);
        if (report == null) {
            throw BizException.serviceUnavailable("EVAL_EMPTY_RESPONSE", "评测引擎未返回有效评测报告");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCases", report.get("total_cases"));
        summary.put("passedCases", report.get("passed_cases"));
        summary.put("overallPassRate", report.get("overall_pass_rate"));
        summary.put("durationMs", report.get("duration_ms"));
        auditService.record(principal, "EVAL_RUN", "GOLDEN_EVAL", "golden_dataset", null, summary);
        return report;
    }
}
