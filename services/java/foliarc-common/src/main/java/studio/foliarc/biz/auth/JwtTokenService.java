package studio.foliarc.biz.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signs and verifies short-lived HS256 Access Tokens using Nimbus JOSE + JWT.
 */
@Service
public class JwtTokenService {

    public static final String AUD_WEB = "foliarc-web";
    public static final String AUD_ADMIN = "foliarc-admin";

    private final JwtProperties properties;
    private final byte[] webSecret;
    private final byte[] adminSecret;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.webSecret = secretBytes(properties.secret(), "foliarc.jwt.secret");
        this.adminSecret = properties.adminSecret() == null || properties.adminSecret().isBlank()
                ? deriveAdminSecret(webSecret)
                : secretBytes(properties.adminSecret(), "foliarc.jwt.admin-secret");
    }

    public String issueAccessToken(AuthenticatedUser user) {
        return issueAccessToken(user, AUD_WEB, properties.accessTtlSeconds());
    }

    public String issueAccessToken(AuthenticatedUser user, String audience, long ttlSeconds) {
        byte[] signingKey = keyForAudience(audience);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(audience)
                .subject(user.userId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("email", user.email())
                .claim("username", user.username())
                .claim("role", user.role())
                .build();

        try {
            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJWT.sign(new MACSigner(signingKey));
            return signedJWT.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign JWT access token", ex);
        }
    }

    public Optional<AuthenticatedUser> parseAccessToken(String token) {
        return parseAccessTokenForAudience(token, AUD_WEB);
    }

    public Optional<AuthenticatedUser> parseAccessTokenForAudience(String token, String expectedAudience) {
        byte[] signingKey = keyForAudience(expectedAudience);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!signedJWT.verify(new MACVerifier(signingKey))) {
                return Optional.empty();
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(Instant.now())) {
                return Optional.empty();
            }

            boolean legacyWeb = AUD_WEB.equals(expectedAudience) && !claims.getClaims().containsKey("aud");
            if (!legacyWeb && !List.of(expectedAudience).equals(claims.getAudience())) {
                return Optional.empty();
            }

            String subject = claims.getSubject();
            String email = claims.getStringClaim("email");
            String username = claims.getStringClaim("username");
            String role = claims.getStringClaim("role");
            if (subject == null || email == null || username == null || role == null
                    || (AUD_ADMIN.equals(expectedAudience) && !"ADMIN".equals(role))) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(UUID.fromString(subject), email, username, role));
        } catch (ParseException | JOSEException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public long accessTtlSeconds() {
        return properties.accessTtlSeconds();
    }

    public long refreshTtlSeconds() {
        return properties.refreshTtlSeconds();
    }

    public boolean cookieSecure() {
        return properties.cookieSecure();
    }

    private byte[] keyForAudience(String audience) {
        return switch (audience) {
            case AUD_WEB -> webSecret;
            case AUD_ADMIN -> adminSecret;
            default -> throw new IllegalArgumentException("Unsupported JWT audience: " + audience);
        };
    }

    private static byte[] secretBytes(String value, String property) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException(property + " must be at least 32 bytes for HS256");
        }
        return bytes;
    }

    private static byte[] deriveAdminSecret(byte[] webSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webSecret, "HmacSHA256"));
            return mac.doFinal(AUD_ADMIN.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to derive the admin JWT key", ex);
        }
    }
}
