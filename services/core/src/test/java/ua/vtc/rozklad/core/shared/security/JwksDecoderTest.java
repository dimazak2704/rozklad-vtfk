package ua.vtc.rozklad.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Forged tokens with random key ids must not make the services download the JWKS from auth on every request. */
class JwksDecoderTest {

    private static final String ISSUER = "http://localhost:8088/auth";
    private static final URI JWKS = URI.create("http://auth:8085/auth/oauth2/jwks");

    private static RSAKey authKey;
    private static RSAKey forgerKey;

    @BeforeAll
    static void keys() throws JOSEException {
        authKey = new RSAKeyGenerator(2048).keyID("2026-11-12-k3f9").generate();
        forgerKey = new RSAKeyGenerator(2048).keyID("forged").generate();
    }

    @Test
    void unknownKeyIdsAreRefusedWithoutRefetchingTheKeySetEachTime() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        ResourceRetriever auth = (URL url) -> {
            fetches.incrementAndGet();
            return new Resource(new JWKSet(authKey.toPublicJWK()).toString(), "application/json");
        };
        JwtDecoder decoder = JwksDecoder.create(JWKS, auth, new AuthProperties(ISSUER, JWKS, "rozklad-api")
                .tokenValidator());

        assertThat(decoder.decode(token(authKey, authKey.getKeyID())).getSubject()).isEqualTo("admin");
        assertThat(fetches).hasValue(1);

        for (int i = 0; i < 20; i++) {
            String forged = token(forgerKey, UUID.randomUUID().toString());
            assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
        }

        // Nimbus allows two retrievals per interval: the first load and one refetch; without the limit it would be 21
        assertThat(fetches).as("fetches within %d ms", JwksDecoder.MIN_REFETCH_INTERVAL_MS).hasValue(2);
        assertThat(decoder.decode(token(authKey, authKey.getKeyID())).getSubject()).isEqualTo("admin");
    }

    private static String token(RSAKey key, String keyId) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("admin").issuer(ISSUER)
                .audience(List.of("rozklad-api")).issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
