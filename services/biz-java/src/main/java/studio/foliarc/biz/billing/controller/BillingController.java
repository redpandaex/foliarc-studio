package studio.foliarc.biz.billing.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.service.BillingService;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/dashboard")
    public BillingDtos.BillingDashboardResponse dashboard(
            @AuthenticationPrincipal AuthenticatedUser actor
    ) {
        return billingService.getDashboard(actor.userId());
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
