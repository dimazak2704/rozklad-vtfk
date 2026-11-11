package ua.vtc.rozklad.auth.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(12, Set.of("password12345", "qwertyuiopas"));

    @Test
    void aLongUncommonPasswordIsAccepted() {
        assertThat(policy.check("Kvitucha-Sliva-42", "ivanenko", "ivanenko@vtc.vn.ua")).isEmpty();
    }

    @Test
    void lengthIsCountedInCharactersNotBytes() {
        assertThat(policy.check("Короткий1", null, null)).hasValue(PasswordPolicy.Reason.TOO_SHORT);
        assertThat(policy.check("Їжак-у-тумані", null, null)).isEmpty();
        assertThat(policy.check("abcdefghijk", null, null)).hasValue(PasswordPolicy.Reason.TOO_SHORT);
        assertThat(policy.check(null, null, null)).hasValue(PasswordPolicy.Reason.TOO_SHORT);
    }

    @Test
    void longerThan64CharactersOr72BytesIsRejected() {
        assertThat(policy.check("a1".repeat(32), null, null)).isEmpty();
        assertThat(policy.check("a1".repeat(32) + "x", null, null)).hasValue(PasswordPolicy.Reason.TOO_LONG);
        // 40 кириличних літер = 80 байтів UTF-8: BCrypt проігнорував би хвіст
        assertThat(policy.check("їжак".repeat(10), null, null)).hasValue(PasswordPolicy.Reason.TOO_LONG);
        assertThat(policy.check("їжак".repeat(9), null, null)).isEmpty();
    }

    @Test
    void theUsernameOrEmailIsNotAPassword() {
        assertThat(policy.check("Petrenko.Ivan", "petrenko.ivan", null)).hasValue(PasswordPolicy.Reason.SAME_AS_USERNAME);
        assertThat(policy.check("Petrenko@vtc.vn.ua", null, "petrenko@vtc.vn.ua"))
                .hasValue(PasswordPolicy.Reason.SAME_AS_EMAIL);
        assertThat(policy.check("petrenkoivan1", null, "PetrenkoIvan1@vtc.vn.ua"))
                .hasValue(PasswordPolicy.Reason.SAME_AS_EMAIL);
    }

    @Test
    void commonAndRepeatedPasswordsAreRejected() {
        assertThat(policy.check("Password12345", null, null)).hasValue(PasswordPolicy.Reason.TOO_COMMON);
        assertThat(policy.check("aaaaaaaaaaaaaa", null, null)).hasValue(PasswordPolicy.Reason.TOO_COMMON);
    }

    @Test
    void theBundledListIsLoadedAndHasTheUsualSuspects() {
        var bundled = new PasswordPolicy(PropertiesFixture.dev());

        assertThat(bundled.check("qwertyuiop123", null, null)).hasValue(PasswordPolicy.Reason.TOO_COMMON);
        assertThat(bundled.check("123456789012", null, null)).hasValue(PasswordPolicy.Reason.TOO_COMMON);
        assertThat(bundled.check("password1234", null, null)).hasValue(PasswordPolicy.Reason.TOO_COMMON);
        assertThat(bundled.check("Vinnytsia-Dzvinok-7", null, null)).isEmpty();
        assertThat(bundled.minLength()).isEqualTo(12);
    }

    @Test
    void reasonCodesAreStable() {
        assertThat(PasswordPolicy.Reason.TOO_SHORT.code()).isEqualTo("too-short");
        assertThat(PasswordPolicy.Reason.TOO_LONG.code()).isEqualTo("too-long");
        assertThat(PasswordPolicy.Reason.SAME_AS_USERNAME.code()).isEqualTo("same-as-username");
        assertThat(PasswordPolicy.Reason.SAME_AS_EMAIL.code()).isEqualTo("same-as-email");
        assertThat(PasswordPolicy.Reason.TOO_COMMON.code()).isEqualTo("too-common");
    }
}
