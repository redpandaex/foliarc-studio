package studio.foliarc.biz.auth.controller;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.dto.AuthDtos.AuthResponse;
import studio.foliarc.biz.auth.dto.AuthDtos.AuthResult;
import studio.foliarc.biz.auth.dto.AuthDtos.LoginRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.RegisterRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.UserProfile;
import studio.foliarc.biz.auth.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String REFRESH_COOKIE_NAME = "foliarc_refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtTokenService jwtTokenService;

    public AuthController(AuthService authService, JwtTokenService jwtTokenService) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.refreshToken()).toString())
                .body(result.response());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.refreshToken()).toString())
                .body(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        AuthResult result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.refreshToken()).toString())
                .body(result.response());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.getProfile(user.userId());
    }

    private ResponseCookie buildRefreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(jwtTokenService.cookieSecure())
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(jwtTokenService.refreshTtlSeconds()))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(jwtTokenService.cookieSecure())
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
