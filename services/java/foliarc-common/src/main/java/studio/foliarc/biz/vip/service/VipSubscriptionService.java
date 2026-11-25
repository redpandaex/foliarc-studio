package studio.foliarc.biz.vip.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.entity.BillingAccountEntity;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.common.TransactionCallbacks;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.entity.UserSubscriptionEntity;
import studio.foliarc.biz.vip.entity.VipOrderEntity;
import studio.foliarc.biz.vip.entity.VipPlanEntity;
import studio.foliarc.biz.vip.mapper.UserSubscriptionMapper;
import studio.foliarc.biz.vip.mapper.VipOrderMapper;
import studio.foliarc.biz.vip.mapper.VipPlanMapper;

@Service
public class VipSubscriptionService {

    private final VipEntitlementService entitlementService;
    private final VipPlanMapper planMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final VipOrderMapper orderMapper;
    private final BillingService billingService;
    private final KnowledgeDocumentMapper documentMapper;

    public VipSubscriptionService(
            VipEntitlementService entitlementService,
            VipPlanMapper planMapper,
            UserSubscriptionMapper subscriptionMapper,
            VipOrderMapper orderMapper,
            BillingService billingService,
            KnowledgeDocumentMapper documentMapper
    ) {
        this.entitlementService = entitlementService;
        this.planMapper = planMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.orderMapper = orderMapper;
        this.billingService = billingService;
        this.documentMapper = documentMapper;
    }

    public List<VipDtos.VipPlanResponse> listPublicPlans() {
        return entitlementService.listAllPlans().stream()
                .map(this::toPlanResponse)
                .toList();
    }

    public List<VipDtos.VipPlanResponse> listAdminPlans() {
        return planMapper.selectList(new LambdaQueryWrapper<VipPlanEntity>()
                        .orderByAsc(VipPlanEntity::getSortOrder, VipPlanEntity::getPlanCode))
                .stream().map(this::toPlanResponse).toList();
    }

    public VipDtos.MyVipStatusResponse getMyStatus(UUID userId) {
        VipDtos.ResolvedUserEntitlements resolved = entitlementService.resolveForUser(userId);
        BillingAccountEntity acct = billingService.ensureAccount(userId);

        List<KnowledgeDocumentEntity> userDocs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getOwnerId, userId)
                        .eq(KnowledgeDocumentEntity::getScope, "USER")
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );
        int docCount = userDocs == null ? 0 : userDocs.size();
        long usedBytes = userDocs == null ? 0L
                : userDocs.stream().mapToLong(d -> d.getFileSize() == null ? 0L : d.getFileSize()).sum();

        VipDtos.VipEntitlements ent = resolved.entitlements();
        return new VipDtos.MyVipStatusResponse(
                resolved.planCode(),
                resolved.displayName(),
                resolved.subscriptionStatus(),
                resolved.startedAt(),
                resolved.expiresAt(),
                resolved.creditDiscountBps(),
                ModelPricing.toCredits(acct.getBalanceMicros() == null ? 0L : acct.getBalanceMicros()),
                entitlementService.getRippleScansUsedToday(userId),
                ent.rippleDailyScanLimit(),
                new VipDtos.LibraryQuotaUsage(
                        docCount,
                        ent.userLibraryMaxDocs(),
                        usedBytes,
                        ent.userLibraryMaxTotalBytes()
                ),
                ent
        );
    }

    @Transactional
    public VipDtos.SubscribeVipResponse subscribe(UUID userId, VipDtos.SubscribeVipRequest req) {
        String planCode = VipEntitlementService.normalizePlanCode(req.planCode());
        if (VipEntitlementService.PLAN_FREE.equals(planCode)) {
            throw BizException.badRequest("INVALID_VIP_PLAN", "免费版无需开通，请选择 VIP / SVIP / STUDIO 套餐");
        }
        VipPlanEntity plan = planMapper.selectById(planCode);
        if (plan == null || !Boolean.TRUE.equals(plan.getEnabled())) {
            throw BizException.notFound("VIP_PLAN_NOT_FOUND", "目标会员套餐不存在或未启用: " + planCode);
        }

        int months = req.months() == null ? 1 : req.months();
        int durationDays = months * 30;
        String channel = (req.paymentChannel() == null || req.paymentChannel().isBlank())
                ? "CREDITS"
                : req.paymentChannel().trim().toUpperCase(Locale.ROOT);
        if (!"CREDITS".equals(channel)) {
            throw BizException.badRequest("UNSUPPORTED_PAYMENT_CHANNEL", "当前自助开通仅支持积分兑换 (CREDITS)");
        }

        String idemKey = (req.idempotencyKey() != null && !req.idempotencyKey().isBlank())
                ? req.idempotencyKey().trim()
                : "SUB:" + userId + ":" + UUID.randomUUID();

        VipOrderEntity existingOrder = findOrderByKey(idemKey);
        if (existingOrder != null) {
            UserSubscriptionEntity curSub = findUserSub(userId);
            long costMicros = centsToCreditMicros(existingOrder.getAmountCents());
            long bonusMicros = (plan.getMonthlyGrantMicros() == null ? 0L : plan.getMonthlyGrantMicros())
                    * Math.max(1, existingOrder.getDurationDays() / 30);
            return new VipDtos.SubscribeVipResponse(
                    existingOrder.getId(),
                    existingOrder.getPlanCode(),
                    plan.getDisplayName(),
                    existingOrder.getDurationDays(),
                    ModelPricing.toCredits(costMicros),
                    ModelPricing.toCredits(bonusMicros),
                    curSub != null ? curSub.getExpiresAt() : null,
                    true
            );
        }

        int amountCents = (plan.getMonthlyPriceCents() == null ? 0 : plan.getMonthlyPriceCents()) * months;
        long costMicros = centsToCreditMicros(amountCents);
        long bonusMicros = (plan.getMonthlyGrantMicros() == null ? 0L : plan.getMonthlyGrantMicros()) * months;

        billingService.chargeForVipSubscription(
                userId,
                costMicros,
                idemKey,
                "开通 " + plan.getDisplayName() + "（" + months + " 个月）"
        );
        if (bonusMicros > 0L) {
            billingService.grantVipCredits(
                    userId,
                    bonusMicros,
                    idemKey,
                    plan.getDisplayName() + " 开通赠送额度（" + months + " 个月）"
            );
        }

        VipOrderEntity order = createOrder(userId, planCode, durationDays, amountCents, channel, idemKey);
        UserSubscriptionEntity sub = upsertSubscription(userId, planCode, durationDays, order.getId().toString());
        TransactionCallbacks.afterCommit(() -> entitlementService.evictUserCache(userId));

        return new VipDtos.SubscribeVipResponse(
                order.getId(),
                planCode,
                plan.getDisplayName(),
                durationDays,
                ModelPricing.toCredits(costMicros),
                ModelPricing.toCredits(bonusMicros),
                sub.getExpiresAt(),
                false
        );
    }

    @Transactional
    public VipDtos.SubscribeVipResponse adminGrant(UUID adminId, VipDtos.AdminGrantVipRequest req) {
        String planCode = VipEntitlementService.normalizePlanCode(req.planCode());
        VipPlanEntity plan = planMapper.selectById(planCode);
        if (plan == null) {
            throw BizException.notFound("VIP_PLAN_NOT_FOUND", "套餐不存在: " + planCode);
        }

        int durationDays = req.durationDays() == null ? 30 : req.durationDays();
        String idemKey = (req.idempotencyKey() != null && !req.idempotencyKey().isBlank())
                ? req.idempotencyKey().trim()
                : "ADMIN_GRANT:" + adminId + ":" + req.userId() + ":" + UUID.randomUUID();

        VipOrderEntity dup = findOrderByKey(idemKey);
        if (dup != null) {
            UserSubscriptionEntity curSub = findUserSub(req.userId());
            return new VipDtos.SubscribeVipResponse(
                    dup.getId(),
                    dup.getPlanCode(),
                    plan.getDisplayName(),
                    dup.getDurationDays(),
                    0.0,
                    0.0,
                    curSub != null ? curSub.getExpiresAt() : null,
                    true
            );
        }

        long bonusMicros = 0L;
        if (Boolean.TRUE.equals(req.grantBonusCredits()) && plan.getMonthlyGrantMicros() != null) {
            int monthsEquiv = Math.max(1, (durationDays + 29) / 30);
            bonusMicros = plan.getMonthlyGrantMicros() * monthsEquiv;
            billingService.grantVipCredits(
                    req.userId(),
                    bonusMicros,
                    idemKey,
                    "管理员授予 " + plan.getDisplayName() + " 附赠额度"
            );
        }

        VipOrderEntity order = createOrder(req.userId(), planCode, durationDays, 0, "ADMIN_GRANT", idemKey);
        UserSubscriptionEntity sub = upsertSubscription(req.userId(), planCode, durationDays, order.getId().toString());
        TransactionCallbacks.afterCommit(() -> entitlementService.evictUserCache(req.userId()));

        return new VipDtos.SubscribeVipResponse(
                order.getId(),
                planCode,
                plan.getDisplayName(),
                durationDays,
                0.0,
                ModelPricing.toCredits(bonusMicros),
                sub.getExpiresAt(),
                false
        );
    }

    @Transactional
    public VipDtos.VipPlanResponse adminUpdatePlan(String rawPlanCode, VipDtos.AdminUpdateVipPlanRequest req) {
        VipPlanEntity plan = requirePlanForUpdate(rawPlanCode);
        if (req.displayName() != null && !req.displayName().isBlank()) {
            plan.setDisplayName(req.displayName().trim());
        }
        applyPlanAmounts(plan, req);
        if (req.creditDiscountBps() != null) {
            plan.setCreditDiscountBps(req.creditDiscountBps());
        }
        if (req.entitlements() != null) {
            plan.setEntitlementsJson(entitlementService.serializeEntitlements(req.entitlements()));
        }
        if (req.enabled() != null) {
            plan.setEnabled(req.enabled());
        }
        plan.setUpdatedAt(OffsetDateTime.now());
        planMapper.updateById(plan);
        TransactionCallbacks.afterCommit(entitlementService::evictAllPlanCaches);
        return toPlanResponse(plan);
    }

    private static void applyPlanAmounts(VipPlanEntity plan, VipDtos.AdminUpdateVipPlanRequest req) {
        try {
            if (req.monthlyPriceCents() != null) {
                plan.setMonthlyPriceCents(req.monthlyPriceCents());
            } else if (req.monthlyPriceCredits() != null) {
                plan.setMonthlyPriceCents(req.monthlyPriceCredits().movePointRight(2).intValueExact());
            }
            if (req.monthlyGrantMicros() != null) {
                plan.setMonthlyGrantMicros(req.monthlyGrantMicros());
            } else if (req.monthlyGrantCredits() != null) {
                plan.setMonthlyGrantMicros(req.monthlyGrantCredits().movePointRight(6).longValueExact());
            }
        } catch (ArithmeticException ex) {
            throw BizException.badRequest("INVALID_VIP_PLAN_AMOUNTS", "会员套餐价格或赠送额度超出可存储的范围或精度");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public VipDtos.VipPlanResponse adminPlanForUpdate(String rawPlanCode) {
        return toPlanResponse(requirePlanForUpdate(rawPlanCode));
    }

    private VipPlanEntity requirePlanForUpdate(String rawPlanCode) {
        String code = VipEntitlementService.normalizePlanCode(rawPlanCode);
        VipPlanEntity plan = planMapper.selectOne(new LambdaQueryWrapper<VipPlanEntity>()
                .eq(VipPlanEntity::getPlanCode, code)
                .last("FOR UPDATE"));
        if (plan == null) {
            throw BizException.notFound("VIP_PLAN_NOT_FOUND", "套餐不存在: " + code);
        }
        return plan;
    }

    private VipOrderEntity createOrder(
            UUID userId,
            String planCode,
            int durationDays,
            int amountCents,
            String channel,
            String idemKey
    ) {
        VipOrderEntity order = new VipOrderEntity();
        order.setId(UUID.randomUUID());
        order.setUserId(userId);
        order.setPlanCode(planCode);
        order.setDurationDays(durationDays);
        order.setAmountCents(amountCents);
        order.setPaymentChannel(channel);
        order.setIdempotencyKey(idemKey);
        order.setStatus("PAID");
        orderMapper.insert(order);
        return order;
    }

    private UserSubscriptionEntity upsertSubscription(
            UUID userId,
            String planCode,
            int durationDays,
            String orderId
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        UserSubscriptionEntity sub = findUserSub(userId);
        boolean isFree = VipEntitlementService.PLAN_FREE.equals(planCode);
        if (sub == null) {
            sub = new UserSubscriptionEntity();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlanCode(planCode);
            sub.setStatus("ACTIVE");
            sub.setStartedAt(now);
            sub.setExpiresAt(isFree ? null : now.plusDays(durationDays));
            sub.setAutoRenew(false);
            sub.setLastOrderId(orderId);
            sub.setVersion(0L);
            subscriptionMapper.insert(sub);
            return sub;
        }

        boolean renewingSameActiveTier = planCode.equalsIgnoreCase(sub.getPlanCode())
                && "ACTIVE".equalsIgnoreCase(sub.getStatus())
                && sub.getExpiresAt() != null
                && sub.getExpiresAt().isAfter(now);
        OffsetDateTime baseTime = renewingSameActiveTier ? sub.getExpiresAt() : now;
        sub.setPlanCode(planCode);
        sub.setStatus("ACTIVE");
        if (!renewingSameActiveTier) {
            sub.setStartedAt(now);
        }
        sub.setExpiresAt(isFree ? null : baseTime.plusDays(durationDays));
        sub.setLastOrderId(orderId);
        subscriptionMapper.updateById(sub);
        return sub;
    }

    private VipOrderEntity findOrderByKey(String idemKey) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<VipOrderEntity>()
                        .eq(VipOrderEntity::getIdempotencyKey, idemKey)
                        .last("LIMIT 1")
        );
    }

    private UserSubscriptionEntity findUserSub(UUID userId) {
        return subscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
    }

    private VipDtos.VipPlanResponse toPlanResponse(VipPlanEntity p) {
        int priceCents = p.getMonthlyPriceCents() == null ? 0 : p.getMonthlyPriceCents();
        long grantMicros = p.getMonthlyGrantMicros() == null ? 0L : p.getMonthlyGrantMicros();
        return new VipDtos.VipPlanResponse(
                p.getPlanCode(),
                p.getDisplayName(),
                priceCents,
                ModelPricing.toCredits(centsToCreditMicros(priceCents)),
                grantMicros,
                ModelPricing.toCredits(grantMicros),
                p.getCreditDiscountBps() == null ? 10000 : p.getCreditDiscountBps(),
                entitlementService.parseEntitlements(p.getPlanCode(), p.getEntitlementsJson()),
                Boolean.TRUE.equals(p.getEnabled()),
                p.getSortOrder() == null ? 0 : p.getSortOrder()
        );
    }

    /**
     * Local credits conversion: 100 cents (= ¥1.00) equals 1.0 Credit (= 1,000,000 micros).
     */
    public static long centsToCreditMicros(int cents) {
        return Math.max(0L, cents) * 10_000L;
    }
}
