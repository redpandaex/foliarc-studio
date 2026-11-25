package studio.foliarc.biz.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class JwtTokenServiceTests {

    private static final String WEB_SECRET = "web-test-signing-key-at-least-32-bytes";
    private static final String ADMIN_SECRET = "admin-test-signing-key-at-least-32-bytes";
    private static final String ISSUER = "foliarc-studio";
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(UUID.randomUUID(), "admin@example.test", "admin", "ADMIN");

    @Test
    void defaultsToWebAndAcceptsLegacyTokensOnlyOnWeb() throws Exception {
        var service = new JwtTokenService(new JwtProperties(WEB_SECRET, ISSUER, 900, 604800, false));
        String webToken = service.issueAccessToken(ADMIN);
        String legacy = sign(WEB_SECRET, null);

        assertThat(SignedJWT.parse(webToken).getJWTClaimsSet().getAudience()).containsExactly(JwtTokenService.AUD_WEB);
        assertThat(service.parseAccessToken(webToken)).contains(ADMIN);
        assertThat(service.parseAccessToken(legacy)).contains(ADMIN);
        assertThat(service.parseAccessTokenForAudience(legacy, JwtTokenService.AUD_ADMIN)).isEmpty();
    }

    @Test
    void rejectsMixedOrWrongAudienceEvenWithTheCorrectSignature() throws Exception {
        var service = new JwtTokenService(new JwtProperties(WEB_SECRET, ISSUER, 900, 604800, false, WEB_SECRET));
        String mixed = sign(WEB_SECRET, List.of(JwtTokenService.AUD_WEB, JwtTokenService.AUD_ADMIN));
        String other = sign(WEB_SECRET, List.of("some-other-app"));
        String web = service.issueAccessToken(ADMIN);
        String admin = service.issueAccessToken(ADMIN, JwtTokenService.AUD_ADMIN, 7200);

        assertThat(service.parseAccessTokenForAudience(mixed, JwtTokenService.AUD_ADMIN)).isEmpty();
        assertThat(service.parseAccessToken(mixed)).isEmpty();
        assertThat(service.parseAccessToken(other)).isEmpty();
        assertThat(service.parseAccessTokenForAudience(web, JwtTokenService.AUD_ADMIN)).isEmpty();
        assertThat(service.parseAccessToken(admin)).isEmpty();
        assertThat(service.parseAccessTokenForAudience(admin, JwtTokenService.AUD_ADMIN)).contains(ADMIN);
    }

    @Test
    void requiresAdminRoleAndUsesTheConfiguredAdminKey() throws Exception {
        var service = new JwtTokenService(new JwtProperties(WEB_SECRET, ISSUER, 900, 604800, false, ADMIN_SECRET));
        var author = new AuthenticatedUser(ADMIN.userId(), ADMIN.email(), ADMIN.username(), "AUTHOR");
        String adminAudience = JwtTokenService.AUD_ADMIN;

        assertThat(service.parseAccessTokenForAudience(sign(WEB_SECRET, List.of(adminAudience)), adminAudience)).isEmpty();
        assertThat(service.parseAccessTokenForAudience(sign(ADMIN_SECRET, List.of(adminAudience)), adminAudience))
                .contains(ADMIN);
        assertThat(service.parseAccessTokenForAudience(service.issueAccessToken(author, adminAudience, 7200), adminAudience))
                .isEmpty();
    }

    private static String sign(String secret, List<String> audiences) throws JOSEException {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(ADMIN.userId().toString())
                .claim("email", ADMIN.email())
                .claim("username", ADMIN.username())
                .claim("role", ADMIN.role())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (audiences != null) {
            claims.audience(audiences);
        }
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
