package ua.vtc.rozklad.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ua.vtc.rozklad.core.shared.time.StandClock;
import ua.vtc.rozklad.core.shared.time.TimeConfig;

/**
 * Under the stand time the domain clock runs days ahead, but access tokens are checked against real
 * time: the production decoder of {@link SecurityConfig} never sees the {@link Clock} bean.
 */
class StandTimeTokenTest {

    private static final String ISSUER = "http://localhost:8088/auth";

    private RSAKey key;
    private HttpServer jwks;
    private JwtDecoder decoder;

    @BeforeEach
    void startJwks() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("dev-1").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/auth/oauth2/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString(true).getBytes(StandardCharsets.UTF_8);
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
    void aTokenValidNowIsAcceptedWhileTheDomainClockIsSixDaysAhead() {
        Instant now = Clock.systemUTC().instant();
        Clock domain = domainClockShiftedBy(Duration.ofDays(6));
        assertThat(StandClock.isShifted(domain)).isTrue();
        assertThat(Duration.between(now, domain.instant())).isBetween(Duration.ofDays(6).minusMinutes(1),
                Duration.ofDays(6).plusMinutes(1));

        Jwt jwt = decoder.decode(token(now, now.plus(Duration.ofMinutes(5))));

        assertThat(jwt.getSubject()).isEqualTo("a0000000-0000-4000-8000-000000000001");
        // the same token judged by the domain clock would have expired days ago
        JwtTimestampValidator onDomainClock = new JwtTimestampValidator();
        onDomainClock.setClock(domain);
        assertThat(onDomainClock.validate(jwt).hasErrors()).isTrue();
    }

    /** The {@link Clock} bean exactly as {@link TimeConfig} builds it from {@code app.stand-time.*}. */
    private static Clock domainClockShiftedBy(Duration shift) {
        String standTime = Clock.system(TimeConfig.KYIV).instant().plus(shift).atZone(TimeConfig.KYIV)
                .toLocalDateTime().toString();
        var environment = new MockEnvironment().withProperty("app.stand-time.at", standTime)
                .withProperty("app.auth.issuer", ISSUER);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(TimeConfig.class);
            context.refresh();
            return context.getBean(Clock.class);
        }
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject("a0000000-0000-4000-8000-000000000001")
                .audience(List.of("rozklad-api")).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("roles", List.of("ADMIN")).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
