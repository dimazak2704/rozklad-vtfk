package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.auth.oauth.ClientRegistrations;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class CspHeaderTest {

    @Test
    void developmentFormActionListsEveryRedirectOriginAndTheCliLoopback() {
        String csp = LoginPagePolicy.contentSecurityPolicy(
                ClientRegistrations.redirectOrigins(PropertiesFixture.builder().cliEnabled(true).build()));

        assertThat(csp).isEqualTo("default-src 'none'; style-src 'self'; img-src 'self' data:; "
                + "font-src 'self' data:; script-src 'self'; "
                + "form-action 'self' http://localhost:5180 http://localhost:5181 http://localhost:8088 http://127.0.0.1:*; "
                + "frame-ancestors 'none'; base-uri 'none'");
    }

    @Test
    void productionFormActionIsTheDomainOnly() {
        String csp = LoginPagePolicy.contentSecurityPolicy(
                ClientRegistrations.redirectOrigins(PropertiesFixture.prod().build()));

        assertThat(csp).contains("form-action 'self' https://rozklad.example.ua;").doesNotContain("127.0.0.1")
                .doesNotContain("unsafe-inline");
    }
}
