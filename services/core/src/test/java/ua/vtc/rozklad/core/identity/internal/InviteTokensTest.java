package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Invite token: 32 random bytes in the link, SHA-256 hex in the database. */
class InviteTokensTest {

    @Test
    void tokensAreUrlSafeAndUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = InviteTokens.newToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(InviteTokens.hasValidFormat(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hashIsSha256HexAndStable() {
        // SHA-256 of "abc" (FIPS 180-2 test vector)
        assertThat(InviteTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = InviteTokens.newToken();
        assertThat(InviteTokens.hash(token)).hasSize(64).isEqualTo(InviteTokens.hash(token)).doesNotContain(token);
    }

    @Test
    void anythingElseIsNotAToken() {
        assertThat(InviteTokens.hasValidFormat(null)).isFalse();
        assertThat(InviteTokens.hasValidFormat("short")).isFalse();
        assertThat(InviteTokens.hasValidFormat("a".repeat(42) + "=")).isFalse();
        assertThat(InviteTokens.hasValidFormat("a".repeat(44))).isFalse();
    }
}
