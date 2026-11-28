package ua.vtc.rozklad.notification.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decodes access tokens with the keys of {@code auth} (JWKS). The key set is cached for five minutes, and
 * tokens with an unknown {@code kid} make it fetched at most twice per {@link #MIN_REFETCH_INTERVAL_MS} (Nimbus's
 * rate limit), so forged tokens with random key ids cannot make every request download the JWKS from {@code auth}.
 * A token refused by the rate limit is an invalid token (401), not a server error. Same as core's
 * {@code JwksDecoder}; the services share no library for it.
 */
final class JwksDecoder implements JwtDecoder {

    /** Nimbus's default; a rotated key (a restart of auth) is picked up within this interval. */
    static final long MIN_REFETCH_INTERVAL_MS = JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL;

    private static final int TIMEOUT_MS = 2_000;
    private static final int SIZE_LIMIT_BYTES = 64 * 1024;

    private final JwtDecoder delegate;

    private JwksDecoder(JwtDecoder delegate) {
        this.delegate = delegate;
    }

    static JwtDecoder create(URI jwksUri, OAuth2TokenValidator<Jwt> validator) {
        return create(jwksUri, new DefaultResourceRetriever(TIMEOUT_MS, TIMEOUT_MS, SIZE_LIMIT_BYTES), validator);
    }

    static JwtDecoder create(URI jwksUri, ResourceRetriever retriever, OAuth2TokenValidator<Jwt> validator) {
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(), retriever)
                    .cache(true)
                    .rateLimited(MIN_REFETCH_INTERVAL_MS)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Bad JWKS URI: " + jwksUri, e);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).build();
        decoder.setJwtValidator(validator);
        return new JwksDecoder(decoder);
    }

    @Override
    public Jwt decode(String token) {
        try {
            return delegate.decode(token);
        } catch (JwtException e) {
            if (causedByRateLimit(e)) {
                throw new BadJwtException("Unknown signing key", e);
            }
            throw e;
        }
    }

    private static boolean causedByRateLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RateLimitReachedException) {
                return true;
            }
        }
        return false;
    }
}
