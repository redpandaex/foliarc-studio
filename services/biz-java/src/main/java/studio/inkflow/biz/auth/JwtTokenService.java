package studio.inkflow.biz.auth;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

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

    private final JwtProperties properties;
    private final byte[] secretBytes;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (this.secretBytes.length < 32) {
            throw new IllegalArgumentException("inkflow.jwt.secret must be at least 32 bytes for HS256");
        }
    }

    public String issueAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.accessTtlSeconds());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
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
            signedJWT.sign(new MACSigner(secretBytes));
            return signedJWT.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign JWT access token", ex);
        }
    }

    public Optional<AuthenticatedUser> parseAccessToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!signedJWT.verify(new MACVerifier(secretBytes))) {
                return Optional.empty();
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }

            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.getStringClaim("email");
            String username = claims.getStringClaim("username");
            String role = claims.getStringClaim("role");
            if (email == null || username == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(userId, email, username, role));
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
}
