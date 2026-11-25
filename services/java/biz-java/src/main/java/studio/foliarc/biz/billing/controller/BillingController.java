package studio.foliarc.biz.billing.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.gateway.RedisRateLimiterService;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;
    private final CircuitBreaker agentCircuitBreaker;
    private final Bulkhead agentBulkhead;
    private final RedisRateLimiterService rateLimiter;

    public BillingController(BillingService billingService, CircuitBreaker agentCircuitBreaker,
                             Bulkhead agentBulkhead, RedisRateLimiterService rateLimiter) {
        this.billingService = billingService;
        this.agentCircuitBreaker = agentCircuitBreaker;
        this.agentBulkhead = agentBulkhead;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/dashboard")
    public BillingDtos.BillingDashboardResponse dashboard(
            @AuthenticationPrincipal AuthenticatedUser actor
    ) {
        var gateway = new BillingDtos.GatewayStatusResponse(
                agentCircuitBreaker.getState().name(),
                Math.max(0.0f, agentCircuitBreaker.getMetrics().getFailureRate()),
                agentBulkhead.getMetrics().getAvailableConcurrentCalls(),
                agentBulkhead.getMetrics().getMaxAllowedConcurrentCalls(),
                rateLimiter.getRpmLimit(),
                rateLimiter.getTpmLimit()
        );
        return billingService.getDashboard(actor.userId(), gateway);
    }

    @GetMapping("/ledger")
    public List<BillingDtos.LedgerEntryResponse> ledger(
            @AuthenticationPrincipal AuthenticatedUser actor
    ) {
        return billingService.listLedger(actor.userId());
    }

    @PostMapping("/topup")
    public BillingDtos.AccountSummary topUp(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody BillingDtos.TopUpRequest body
    ) {
        return billingService.topUp(actor.userId(), body.amountCredits(), body.requestId());
    }

    @PutMapping("/budget")
    public BillingDtos.AccountSummary updateBudget(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody BillingDtos.UpdateBudgetRequest body
    ) {
        return billingService.updateMonthlyBudget(actor.userId(), body.monthlyBudgetCredits());
    }
}
