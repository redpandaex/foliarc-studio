package studio.foliarc.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.dto.AuthDtos.AuthResponse;
import studio.foliarc.biz.auth.dto.AuthDtos.LoginRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.UserProfile;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.service.UserAccountService;
import studio.foliarc.biz.common.BizException;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private static final long ACCESS_TTL_SECONDS = 7200;

    private final UserAccountService userAccountService;
    private final JwtTokenService jwtTokenService;

    public AdminAuthController(UserAccountService userAccountService, JwtTokenService jwtTokenService) {
        this.userAccountService = userAccountService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = userAccountService.authenticate(request);
        requireAdmin(user);
        String token = jwtTokenService.issueAccessToken(
                UserAccountService.toPrincipal(user), JwtTokenService.AUD_ADMIN, ACCESS_TTL_SECONDS);
        return new AuthResponse(token, ACCESS_TTL_SECONDS, UserAccountService.toProfile(user));
    }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal AuthenticatedUser principal) {
        UserEntity user = userAccountService.requireUser(principal.userId());
        requireAdmin(user);
        return UserAccountService.toProfile(user);
    }

    private static void requireAdmin(UserEntity user) {
        if (!"ADMIN".equals(user.getRole())) {
            throw BizException.forbidden("ADMIN_REQUIRED", "仅管理员可访问该后台接口");
        }
    }
}
