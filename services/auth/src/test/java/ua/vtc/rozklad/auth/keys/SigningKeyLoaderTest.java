package ua.vtc.rozklad.auth.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.vtc.rozklad.auth.config.AuthServerProperties.SigningKey;

class SigningKeyLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsAPkcs8PemAndDerivesThePublicKey() throws Exception {
        KeyPair pair = rsa(2048);
        Path file = write("active.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());

        RSAKey key = SigningKeyLoader.active(new SigningKey(file.toString(), "2026-11-a", null, null), true);

        assertThat(key.getKeyID()).isEqualTo("2026-11-a");
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(key.toPublicJWK().isPrivate()).isFalse();
    }

    @Test
    void withoutAFileAnHttpIssuerGetsAnInMemoryKeyAndHttpsFails() {
        RSAKey generated = SigningKeyLoader.active(new SigningKey("", "dev", null, null), false);

        assertThat(generated.isPrivate()).isTrue();
        assertThat(generated.getKeyID()).isEqualTo("dev");
        assertThatThrownBy(() -> SigningKeyLoader.active(new SigningKey(null, "k", null, null), true))
                .hasMessageContaining("AUTH_SIGNING_KEY_FILE");
    }

    @Test
    void theWrongPemTypeOrAShortKeyIsRefused() throws Exception {
        KeyPair small = rsa(1024);
        Path pkcs1 = Files.writeString(dir.resolve("rsa.pem"), "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        Path weak = write("weak.pem", "PRIVATE KEY", small.getPrivate().getEncoded());

        assertThatThrownBy(() -> SigningKeyLoader.active(new SigningKey(pkcs1.toString(), "k", null, null), true))
                .hasMessageContaining("PKCS#8");
        assertThatThrownBy(() -> SigningKeyLoader.active(new SigningKey(weak.toString(), "k", null, null), true))
                .hasMessageContaining("2048");
    }

    @Test
    void thePreviousPublicKeyIsPublishedDuringARotation() throws Exception {
        KeyPair previous = rsa(2048);
        Path file = write("previous.pem", "PUBLIC KEY", previous.getPublic().getEncoded());

        RSAKey key = SigningKeyLoader.previous(new SigningKey(null, "new", file.toString(), "2026-05-a")).orElseThrow();

        assertThat(key.getKeyID()).isEqualTo("2026-05-a");
        assertThat(key.isPrivate()).isFalse();
        assertThat(SigningKeyLoader.previous(new SigningKey(null, "new", null, null))).isEmpty();
        assertThatThrownBy(() -> SigningKeyLoader.previous(new SigningKey(null, "new", file.toString(), "")))
                .hasMessageContaining("AUTH_PREVIOUS_KEY_ID");
    }

    private Path write(String name, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return Files.writeString(dir.resolve(name), "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }

    private static KeyPair rsa(int bits) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }
}
