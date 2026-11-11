package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Small pure parts of the sign-in: client networks, error messages, identifier hashing. */
class LoginUnitsTest {

    @Test
    void ipv4NetworksAreSlash24AndIpv6Slash64() {
        assertThat(ClientAddress.parse("198.51.100.77")).isEqualTo(new ClientAddress("198.51.100.77", "198.51.100.0/24"));
        assertThat(ClientAddress.parse("2001:db8:1:2:3:4:5:6").prefix()).isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(ClientAddress.parse("not an address")).isEqualTo(new ClientAddress(null, null));
        assertThat(ClientAddress.parse(null)).isEqualTo(new ClientAddress(null, null));
    }

    @Test
    void errorCodesMapToFixedUkrainianMessages() {
        assertThat(LoginController.message(null, false)).isNull();
        assertThat(LoginController.message("", false)).isEqualTo("Неправильний логін або пароль.");
        assertThat(LoginController.message("<b>anything</b>", false)).isEqualTo("Неправильний логін або пароль.");
        assertThat(LoginController.message("throttled_password_only", true)).isEqualTo("Неправильний логін або пароль.");
        assertThat(LoginController.message("throttled", false)).isEqualTo("Забагато спроб. Спробуйте пізніше.");
        assertThat(LoginController.message("throttled", true))
                .isEqualTo("Забагато спроб. Спробуйте пізніше або увійдіть через Google.");
        assertThat(LoginController.message("not_allowed", true))
                .isEqualTo("Цієї адреси немає у списку доступу. Зверніться до навчальної частини.");
        assertThat(LoginController.message("google", true)).isEqualTo("Не вдалося увійти через Google. Спробуйте ще раз.");
    }

    @Test
    void identifiersAreHashedAfterTrimmingAndLowerCasing() {
        assertThat(LoginAttempts.hash("  Krentsin ")).isEqualTo(LoginAttempts.hash("krentsin")).hasSize(32);
        assertThat(LoginAttempts.hash(null)).isEqualTo(LoginAttempts.hash(""));
    }
}
