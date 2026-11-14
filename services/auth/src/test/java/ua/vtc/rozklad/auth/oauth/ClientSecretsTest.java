package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class ClientSecretsTest {

    private final PasswordEncoder encoder = ClientSecrets.encoder();

    @Test
    void secretsAreStoredWithTheLowBcryptCost() {
        String stored = encoder.encode("test-only-notification-secret-32");

        assertThat(stored).startsWith("{bcrypt}$2a$04$");
        assertThat(encoder.matches("test-only-notification-secret-32", stored)).isTrue();
        assertThat(encoder.matches("test-only-notification-secret-33", stored)).isFalse();
        assertThat(encoder.upgradeEncoding(stored)).isFalse();
    }

    /** A row written before the change (cost 12) still matches and is not re-encoded on use. */
    @Test
    void anOlderCostTwelveHashStillMatches() {
        String older = "{bcrypt}" + new BCryptPasswordEncoder(12).encode("rotated-secret");

        assertThat(encoder.matches("rotated-secret", older)).isTrue();
        assertThat(encoder.upgradeEncoding(older)).isFalse();
    }
}
