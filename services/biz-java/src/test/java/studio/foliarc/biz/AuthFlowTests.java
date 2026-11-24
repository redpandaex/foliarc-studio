package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtProperties;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.RefreshTokenRecord;
import studio.foliarc.biz.auth.RefreshTokenStore;
import studio.foliarc.biz.auth.dto.AuthDtos;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.auth.service.AuthService;
import studio.foliarc.biz.common.BizException;

class AuthFlowTests {

    private static final String TEST_SECRET = "test-only-foliarc-jwt-secret-key-with-at-least-32-bytes";

    @Test
    void jwtRoundTripAndTamperRejection() {
        JwtTokenService jwt = new JwtTokenService(new JwtProperties(TEST_SECRET, "foliarc-studio", 900, 604800, false));
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), "author@foliarc.dev", "墨客", "AUTHOR");

        String token = jwt.issueAccessToken(actor);
        assertThat(jwt.parseAccessToken(token)).contains(actor);
        assertThat(jwt.parseAccessToken(token + "tampered")).isEmpty();

        JwtTokenService expiredService = new JwtTokenService(new JwtProperties(TEST_SECRET, "foliarc-studio", -10, 604800, false));
        String expiredToken = expiredService.issueAccessToken(actor);
        assertThat(jwt.parseAccessToken(expiredToken)).isEmpty();
    }

    @Test
    void refreshRotationAndReuseDetectionRevokesEntireFamily() {
        UserMapper userMapper = mock(UserMapper.class);
        InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();
        JwtTokenService jwt = new JwtTokenService(new JwtProperties(TEST_SECRET, "foliarc-studio", 900, 604800, false));
        AuthService authService = new AuthService(userMapper, new BCryptPasswordEncoder(), jwt, store);

        Map<UUID, UserEntity> db = new ConcurrentHashMap<>();
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity entity = inv.getArgument(0);
            db.put(entity.getId(), entity);
            return 1;
        });
        when(userMapper.selectById(any(UUID.class))).thenAnswer(inv -> db.get(inv.getArgument(0)));

        AuthDtos.AuthResult first = authService.register(
                new AuthDtos.RegisterRequest("writer@foliarc.dev", "青衫客", "StrongPass123!")
        );
        assertThat(store.tokens).doesNotContainKey(first.refreshToken());
        assertThat(store.tokens).containsKey(AuthService.sha256Hex(first.refreshToken()));

        AuthDtos.AuthResult rotated = authService.refresh(first.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(rotated.response().accessToken()).isNotBlank();

        // Replaying the already-rotated token must revoke the whole session family
        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(biz.getCode()).isEqualTo("REFRESH_TOKEN_REUSED");
                });

        // Even the newer rotated token from the compromised family is now revoked
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    private static final class InMemoryRefreshTokenStore implements RefreshTokenStore {
        private final Map<String, RefreshTokenRecord> tokens = new ConcurrentHashMap<>();

        @Override
        public void save(String tokenHash, RefreshTokenRecord record, Duration ttl) {
            tokens.put(tokenHash, record);
        }

        @Override
        public Optional<RefreshTokenRecord> find(String tokenHash) {
            return Optional.ofNullable(tokens.get(tokenHash));
        }

        @Override
        public void markRotated(String tokenHash, RefreshTokenRecord record, Duration ttl) {
            tokens.put(tokenHash, new RefreshTokenRecord(record.userId(), record.familyId(), true));
        }

        @Override
        public void revokeFamily(String familyId) {
            tokens.entrySet().removeIf(entry -> familyId.equals(entry.getValue().familyId()));
        }
    }
}
