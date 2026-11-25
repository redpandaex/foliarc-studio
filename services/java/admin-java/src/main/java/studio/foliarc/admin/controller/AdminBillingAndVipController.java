package studio.foliarc.admin.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import jakarta.validation.Valid;
import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.admin.service.AdminReportingService;
import studio.foliarc.admin.service.AdminReportingService.BillingOverview;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.service.UserAccountService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.entity.UserSubscriptionEntity;
import studio.foliarc.biz.vip.mapper.UserSubscriptionMapper;
import studio.foliarc.biz.vip.service.VipSubscriptionService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminBillingAndVipController {

    private final AdminReportingService reportingService;
    private final VipSubscriptionService subscriptionService;
    private final UserSubscriptionMapper subscriptionMapper;
    private final UserAccountService userAccountService;
    private final AdminAuditService auditService;

    public AdminBillingAndVipController(AdminReportingService reportingService, VipSubscriptionService subscriptionService,
                                       UserSubscriptionMapper subscriptionMapper, UserAccountService userAccountService,
                                       AdminAuditService auditService) {
        this.reportingService = reportingService;
        this.subscriptionService = subscriptionService;
        this.subscriptionMapper = subscriptionMapper;
        this.userAccountService = userAccountService;
        this.auditService = auditService;
    }

    @GetMapping("/billing/overview")
    public BillingOverview billingOverview() {
        return reportingService.billingOverview();
    }

    @GetMapping("/vip/plans")
    public List<VipDtos.VipPlanResponse> listPlans() {
        return subscriptionService.listAdminPlans();
    }

    @PutMapping("/vip/plans/{planCode}")
    @Transactional
    public VipDtos.VipPlanResponse updatePlan(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String planCode,
            @Valid @RequestBody VipDtos.AdminUpdateVipPlanRequest request
    ) {
        var before = subscriptionService.adminPlanForUpdate(planCode);
        var after = subscriptionService.adminUpdatePlan(planCode, request);
        auditService.record(principal, "VIP_PLAN_UPDATE", "VIP_PLAN", after.planCode(), before, after);
        return after;
    }

    @PostMapping("/vip/grant")
    @Transactional
    public VipDtos.SubscribeVipResponse grant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody VipDtos.AdminGrantVipRequest request
    ) {
        userAccountService.requireUser(request.userId());
        var beforeSubscription = subscriptionMapper.selectOne(Wrappers.<UserSubscriptionEntity>lambdaQuery()
                .eq(UserSubscriptionEntity::getUserId, request.userId())
                .last("FOR UPDATE"));
        var after = subscriptionService.adminGrant(principal.userId(), request);
        auditService.record(principal, "VIP_GRANT", "USER", request.userId(), beforeSubscription, after);
        return after;
    }
}
