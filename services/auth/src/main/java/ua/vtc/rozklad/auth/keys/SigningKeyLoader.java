package ua.vtc.rozklad.auth.keys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Loads the token signing key (D7): the active RSA private key from a PKCS#8 PEM file and, during a
 * rotation, the previous public key (X.509 {@code PUBLIC KEY} PEM). Without a file and with an http
 * issuer a fresh in-memory key is generated (development) with a {@code kid} unique to this start, so
 * resource servers that cached the previous key fetch the JWKS again instead of failing the signature
 * check under the old {@code kid}; {@code ProdGuard} refuses that with https. The key material is never
 * logged.
 */
public final class SigningKeyLoader {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyLoader.class);

    private SigningKeyLoader() {
    }

    /** The active key pair as a JWK with {@code kid}, {@code use=sig} and {@code alg=RS256}. */
    public static RSAKey active(AuthServerProperties.SigningKey config, boolean secureIssuer) {
        String keyId = StringUtils.hasText(config.keyId()) ? config.keyId() : "dev";
        if (!StringUtils.hasText(config.file())) {
            if (secureIssuer) {
                throw new IllegalStateException("AUTH_SIGNING_KEY_FILE is required with an https issuer");
            }
            String startKeyId = keyId + "-" + UUID.randomUUID().toString().substring(0, 8);
            log.warn("AUTH_SIGNING_KEY_FILE is not set: using an in-memory signing key '{}' (development only); "
                    + "a restart invalidates the access tokens already issued", startKeyId);
            return generated(startKeyId);
        }
        RSAPrivateCrtKey privateKey = readPrivateKey(Path.of(config.file()));
        return new RSAKey.Builder(publicOf(privateKey)).privateKey(privateKey).keyID(keyId)
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
    }

    /** The previous public key, published in the JWKS during a rotation. */
    public static Optional<RSAKey> previous(AuthServerProperties.SigningKey config) {
        if (!StringUtils.hasText(config.previousPublicKeyFile())) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(config.previousKeyId())) {
            throw new IllegalStateException("AUTH_PREVIOUS_KEY_ID is required with AUTH_PREVIOUS_PUBLIC_KEY_FILE");
        }
        RSAPublicKey key = readPublicKey(Path.of(config.previousPublicKeyFile()));
        return Optional.of(new RSAKey.Builder(key).keyID(config.previousKeyId())
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
    }

    static RSAKey generated(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cannot generate an RSA key", ex);
        }
    }

    static RSAPrivateCrtKey readPrivateKey(Path file) {
        byte[] der = pem(file, "PRIVATE KEY");
        try {
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("signing key is not an RSA CRT private key: " + file);
            }
            if (crt.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("signing key must be at least 2048 bits: " + file);
            }
            return crt;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("signing key is not a PKCS#8 RSA private key: " + file, ex);
        }
    }

    static RSAPublicKey readPublicKey(Path file) {
        byte[] der = pem(file, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException("previous key is not an RSA public key PEM: " + file, ex);
        }
    }

    private static RSAPublicKey publicOf(RSAPrivateCrtKey key) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cannot derive the public key", ex);
        }
    }

    /** The DER bytes between {@code -----BEGIN <type>-----} and {@code -----END <type>-----}. */
    private static byte[] pem(Path file, String type) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read key file " + file, ex);
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalStateException("key file " + file + " is not a PEM '" + type + "'"
                    + ("PRIVATE KEY".equals(type) ? " (PKCS#8: openssl genpkey -algorithm RSA)" : ""));
        }
        String base64 = text.substring(from + begin.length(), to).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
