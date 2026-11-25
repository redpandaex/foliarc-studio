package studio.foliarc.biz.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "foliarc.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        long accessTtlSeconds,
        long refreshTtlSeconds,
        boolean cookieSecure,
        String adminSecret
) {
    @ConstructorBinding
    public JwtProperties {
    }

    public JwtProperties(String secret, String issuer, long accessTtlSeconds, long refreshTtlSeconds,
                         boolean cookieSecure) {
        this(secret, issuer, accessTtlSeconds, refreshTtlSeconds, cookieSecure, null);
    }
}
