package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The decoder of {@link SecurityConfig} against a JWKS endpoint like the one of the sign-in service:
 * tokens of the issuer with {@code aud=rozklad-api} pass, and a token signed with a new key ({@code kid}
 * unknown to the cached JWK set, e.g. after {@code auth} restarted with an in-memory key or a rotation)
 * validates without restarting this service, because the decoder fetches the JWKS again.
 */
class TokenIssuerTest {

    private static final String ISSUER = "http://localhost:8088/auth";

    private final AtomicReference<JWKSet> published = new AtomicReference<>();
    private final AtomicInteger fetches = new AtomicInteger();
    private HttpServer jwks;
    private JwtDecoder decoder;

    @BeforeEach
    void startJwks() throws Exception {
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/auth/oauth2/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] body = published.get().toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        URI uri = URI.create("http://127.0.0.1:" + jwks.getAddress().getPort() + "/auth/oauth2/jwks");
        decoder = new SecurityConfig().jwtDecoder(new AuthProperties(ISSUER, uri, "rozklad-api"));
    }

    @AfterEach
    void stopJwks() {
        jwks.stop(0);
    }

    @Test
    void tokenSignedWithANewKidIsAcceptedAfterARefetch() throws Exception {
        RSAKey first = key("dev-1");
        published.set(new JWKSet(first.toPublicJWK()));
        assertThat(decoder.decode(token(first, ISSUER, List.of("rozklad-web", "rozklad-api"))).getSubject())
                .isEqualTo("a0000000-0000-4000-8000-000000000001");

        // auth started again with a new key: only the new public key is published now
        RSAKey second = key("dev-2");
        published.set(new JWKSet(second.toPublicJWK()));
        assertThat(decoder.decode(token(second, ISSUER, List.of("rozklad-web", "rozklad-api"))).getSubject())
                .isEqualTo("a0000000-0000-4000-8000-000000000001");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void wrongIssuerOrMissingAudienceIsRejected() throws Exception {
        RSAKey active = key("dev-1");
        published.set(new JWKSet(active.toPublicJWK()));

        assertThatThrownBy(() -> decoder.decode(token(active, ISSUER, List.of("rozklad-web"))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(token(active, "http://localhost:8180/realms/rozklad", List.of("rozklad-api"))))
                .isInstanceOf(JwtValidationException.class);
    }

    /** An expired token of the right issuer and key is refused: on a public path that is a 401, not a visitor. */
    @Test
    void expiredTokenIsRejected() throws Exception {
        RSAKey active = key("dev-1");
        published.set(new JWKSet(active.toPublicJWK()));
        Instant issued = Instant.now().minusSeconds(3600);

        assertThatThrownBy(() -> decoder.decode(token(active, ISSUER, List.of("rozklad-api"), issued,
                issued.plusSeconds(900))))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    private static RSAKey key(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).generate();
    }

    private static String token(RSAKey key, String issuer, List<String> audience) {
        return token(key, issuer, audience, Instant.now(), Instant.now().plusSeconds(300));
    }

    private static String token(RSAKey key, String issuer, List<String> audience, Instant issuedAt,
                                Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("a0000000-0000-4000-8000-000000000001")
                .audience(audience).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("roles", List.of("ADMIN")).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
