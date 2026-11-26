package studio.foliarc.admin.controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.admin.service.AdminReportingService;
import studio.foliarc.admin.service.AdminReportingService.AdminUser;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.auth.service.UserAccountService;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.service.BookService;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminReportingService reportingService;
    private final UserMapper userMapper;
    private final UserAccountService userAccountService;
    private final BookService bookService;
    private final BillingService billingService;
    private final AdminAuditService auditService;

    public AdminUserController(AdminReportingService reportingService, UserMapper userMapper,
                               UserAccountService userAccountService, BookService bookService,
                               BillingService billingService, AdminAuditService auditService) {
        this.reportingService = reportingService;
        this.userMapper = userMapper;
        this.userAccountService = userAccountService;
        this.bookService = bookService;
        this.billingService = billingService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<AdminUser> listUsers(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String role,
                                     @RequestParam(defaultValue = "50") int limit) {
        return reportingService.listUsers(q, role, limit);
    }

    @PutMapping("/{userId}/role")
    @Transactional
    public UserRole updateRole(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID userId,
                               @RequestBody RoleChangeRequest body) {
        String newRole = body.role();
        if (!"ADMIN".equals(newRole) && !"AUTHOR".equals(newRole)) {
            throw BizException.badRequest("INVALID_ROLE", "角色仅支持 ADMIN 或 AUTHOR");
        }
        if (principal.userId().equals(userId) && !"ADMIN".equals(newRole)) {
            throw BizException.badRequest("CANNOT_DEMOTE_SELF", "不能取消自己的管理员权限");
        }

        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getId, userId).last("FOR NO KEY UPDATE"));
        if (user == null) {
            throw BizException.notFound("USER_NOT_FOUND", "用户不存在");
        }
        String priorRole = user.getRole();
        UserEntity update = new UserEntity();
        update.setId(userId);
        update.setRole(newRole);
        userMapper.updateById(update);
        auditService.record(principal, "USER_ROLE_CHANGE", "USER", userId,
                Map.of("role", priorRole), Map.of("role", newRole));
        return new UserRole(userId, user.getEmail(), user.getUsername(), newRole);
    }

    @PostMapping("/{userId}/credits")
    @Transactional
    public BillingDtos.AccountSummary grantCredits(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable UUID userId, @Valid @RequestBody CreditGrantRequest body) {
        userAccountService.requireUser(userId);
        BillingDtos.AccountSummary account = billingService.topUp(
                userId, body.amountCredits().doubleValue(), "admin-grant-" + UUID.randomUUID());
        auditService.record(principal, "USER_CREDIT_GRANT", "USER", userId, null, Map.of(
                "amountCredits", body.amountCredits(), "reason", body.reason() == null ? "" : body.reason().strip(),
                "balanceCredits", account.balanceCredits()));
        return account;
    }

    @GetMapping("/{userId}/books")
    public List<AdminUserBook> listBooks(@PathVariable UUID userId) {
        return bookService.listBooks(userId).stream()
                .map(book -> new AdminUserBook(book.id(), book.title(), book.genreCode(),
                        book.chapterCount(), book.totalWordCount(), book.updatedAt()))
                .toList();
    }

    public record RoleChangeRequest(String role) {}

    public record CreditGrantRequest(@NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal amountCredits,
                                     String reason) {}

    public record UserRole(UUID id, String email, String username, String role) {}

    public record AdminUserBook(UUID id, String title, String genreCode, int chapterCount, long totalWordCount,
                               OffsetDateTime updatedAt) {}
}
