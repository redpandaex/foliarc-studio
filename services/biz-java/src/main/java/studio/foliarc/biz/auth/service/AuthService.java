package studio.foliarc.biz.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.RefreshTokenRecord;
import studio.foliarc.biz.auth.RefreshTokenStore;
import studio.foliarc.biz.auth.dto.AuthDtos.AuthResponse;
import studio.foliarc.biz.auth.dto.AuthDtos.AuthResult;
import studio.foliarc.biz.auth.dto.AuthDtos.LoginRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.RegisterRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.UserProfile;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.common.BizException;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenStore refreshTokenStore;

    public AuthService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenStore refreshTokenStore
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenStore = refreshTokenStore;
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email)
        );
        if (existing != null && existing > 0) {
            throw BizException.conflict("EMAIL_ALREADY_EXISTS", "该邮箱已被注册");
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setUsername(request.username().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("AUTHOR");
        userMapper.insert(user);

        return issueSession(user, UUID.randomUUID().toString());
    }

    public AuthResult login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email)
        );
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("INVALID_CREDENTIALS", "邮箱或密码错误");
        }
        return issueSession(user, UUID.randomUUID().toString());
    }

    /**
     * Rotates the Refresh Token in Redis and revokes the entire token family if reuse is detected.
     */
    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw BizException.unauthorized("REFRESH_TOKEN_MISSING", "缺少刷新令牌，请重新登录");
        }

        String tokenHash = sha256Hex(rawRefreshToken);
        RefreshTokenRecord record = refreshTokenStore.find(tokenHash)
                .orElseThrow(() -> BizException.unauthorized("REFRESH_TOKEN_INVALID", "刷新令牌已失效，请重新登录"));

        if (record.rotated()) {
            refreshTokenStore.revokeFamily(record.familyId());
            throw BizException.unauthorized("REFRESH_TOKEN_REUSED", "检测到令牌重复使用，已注销当前会话，请重新登录");
        }

        Duration ttl = Duration.ofSeconds(jwtTokenService.refreshTtlSeconds());
        refreshTokenStore.markRotated(tokenHash, record, ttl);

        UserEntity user = userMapper.selectById(record.userId());
        if (user == null) {
            refreshTokenStore.revokeFamily(record.familyId());
            throw BizException.unauthorized("USER_NOT_FOUND", "用户不存在，请重新登录");
        }

        return issueSession(user, record.familyId());
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String tokenHash = sha256Hex(rawRefreshToken);
        refreshTokenStore.find(tokenHash)
                .ifPresent(record -> refreshTokenStore.revokeFamily(record.familyId()));
    }

    public UserProfile getProfile(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("USER_NOT_FOUND", "用户不存在");
        }
        return toProfile(user);
    }

    private AuthResult issueSession(UserEntity user, String familyId) {
        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRole()
        );
        String accessToken = jwtTokenService.issueAccessToken(principal);
        String rawRefreshToken = generateOpaqueToken();
        String refreshHash = sha256Hex(rawRefreshToken);

        Duration refreshTtl = Duration.ofSeconds(jwtTokenService.refreshTtlSeconds());
        refreshTokenStore.save(refreshHash, new RefreshTokenRecord(user.getId(), familyId, false), refreshTtl);

        AuthResponse response = new AuthResponse(accessToken, jwtTokenService.accessTtlSeconds(), toProfile(user));
        return new AuthResult(response, rawRefreshToken);
    }

    private static UserProfile toProfile(UserEntity user) {
        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRole(),
                user.getCreatedAt()
        );
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
