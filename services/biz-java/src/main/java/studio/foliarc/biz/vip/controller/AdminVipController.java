package studio.foliarc.biz.vip.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipSubscriptionService;

@RestController
@RequestMapping("/api/v1/admin/vip")
public class AdminVipController {

    private final VipSubscriptionService subscriptionService;

    public AdminVipController(VipSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/grant")
    public VipDtos.SubscribeVipResponse grant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody VipDtos.AdminGrantVipRequest req
    ) {
        requireAdmin(principal);
        return subscriptionService.adminGrant(principal.userId(), req);
    }

    @PutMapping("/plans/{planCode}")
    public VipDtos.VipPlanResponse updatePlan(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String planCode,
            @Valid @RequestBody VipDtos.AdminUpdateVipPlanRequest req
    ) {
        requireAdmin(principal);
        return subscriptionService.adminUpdatePlan(planCode, req);
    }

    private static void requireAdmin(AuthenticatedUser principal) {
        if (principal == null || !"ADMIN".equalsIgnoreCase(principal.role())) {
            throw BizException.forbidden("ADMIN_REQUIRED", "仅管理员可访问该后台接口");
        }
    }
}
