package ua.vtc.rozklad.auth.keys;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Keys (DESIGN §2.6). The {@link JWKSource} publishes the active key and, during a rotation, the previous
 * public key (JWKS endpoint, in-process decoder). The {@link JwtEncoder} sees only the active private key,
 * so signing never has to choose; Spring AS builds its {@code JwtGenerator} from this encoder bean.
 */
@Configuration(proxyBeanMethods = false)
public class KeysConfig {

    @Bean
    RSAKey activeSigningKey(AuthServerProperties properties) {
        return SigningKeyLoader.active(properties.signingKey(), properties.secure());
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey activeSigningKey, AuthServerProperties properties) {
        List<JWK> keys = new ArrayList<>();
        keys.add(activeSigningKey);
        SigningKeyLoader.previous(properties.signingKey()).ifPresent(keys::add);
        return new ImmutableJWKSet<>(new JWKSet(keys));
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey activeSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(activeSigningKey)));
    }

    /** Validates our own tokens in-process (userinfo, internal API): signature, exp/nbf and {@code iss}. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, AuthServerProperties properties) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuerValue()));
        return decoder;
    }
}
