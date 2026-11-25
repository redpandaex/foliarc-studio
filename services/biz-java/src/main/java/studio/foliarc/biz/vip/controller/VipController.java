package studio.foliarc.biz.vip.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipSubscriptionService;

@RestController
@RequestMapping("/api/v1/vip")
public class VipController {

    private final VipSubscriptionService subscriptionService;

    public VipController(VipSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/plans")
    public List<VipDtos.VipPlanResponse> listPlans() {
        return subscriptionService.listPublicPlans();
    }

    @GetMapping("/me")
    public VipDtos.MyVipStatusResponse getMyStatus(@AuthenticationPrincipal AuthenticatedUser principal) {
        return subscriptionService.getMyStatus(principal.userId());
    }

    @PostMapping("/subscribe")
    public VipDtos.SubscribeVipResponse subscribe(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody VipDtos.SubscribeVipRequest req
    ) {
        return subscriptionService.subscribe(principal.userId(), req);
    }
}
