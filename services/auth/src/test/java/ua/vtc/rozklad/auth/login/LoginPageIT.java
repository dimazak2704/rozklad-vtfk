package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browsers;

/** The rendered sign-in page: Ukrainian texts, the shared stylesheet, CSP and no inline script or style. */
@AuthIntegrationTest
class LoginPageIT {

    @Autowired
    private Browsers browsers;

    @Test
    void theUkrainianPageLinksTheSharedStylesheetAndPostsWithCsrf() {
        HttpResponse<String> page = browsers.open().get("/auth/login", "Accept", "text/html");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        String html = page.body();
        assertThat(html).contains("<html lang=\"uk\"")
                .contains("<title>Вхід — Розклад ВТФК</title>")
                .contains("Вхід до розкладу")
                .contains("Студентам вхід не потрібен")
                .contains("Логін або e-mail")
                .contains("autocomplete=\"username\"")
                .contains("autocomplete=\"current-password\"")
                .contains(">Увійти</button>")
                .contains("Забули пароль? Зверніться до навчальної частини.")
                .contains("href=\"http://localhost:8088\"")
                .contains("<link rel=\"stylesheet\" href=\"/brand/auth.css\">")
                .contains("src=\"/auth/ui/theme.js\"")
                .contains("action=\"/auth/login\"")
                .contains("name=\"_csrf\"");
        // Google вимкнено (немає client-id): кнопки немає, форма пароля відкрита
        assertThat(html).doesNotContain("Увійти через Google").doesNotContain("<details");
        assertThat(html).doesNotContain("role=\"alert\"");
    }

    @Test
    void noInlineScriptsOrStyles() {
        String html = browsers.open().get("/auth/login").body();

        Matcher scripts = Pattern.compile("<script([^>]*)>").matcher(html);
        int count = 0;
        while (scripts.find()) {
            assertThat(scripts.group(1)).as("every script has src").contains("src=");
            count++;
        }
        assertThat(count).isEqualTo(2);
        assertThat(html).doesNotContain("<style").doesNotContain(" style=").doesNotContain(" onclick=");
    }

    @Test
    void securityHeadersAndCspWithTheRedirectOriginsInFormAction() {
        HttpResponse<String> page = browsers.open().get("/auth/login");

        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("default-src 'none'").contains("script-src 'self'").contains("style-src 'self'")
                .contains("frame-ancestors 'none'").contains("base-uri 'none'")
                .contains("form-action 'self' http://localhost:5180 http://localhost:5181 http://localhost:8088 "
                        + "http://127.0.0.1:*");
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(page.headers().firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
        assertThat(page.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test
    void errorCodesMapToFixedMessagesAndNothingIsEchoed() {
        var browser = browsers.open();

        String wrong = browser.get("/auth/login?error").body();
        String throttled = browser.get("/auth/login?error=throttled").body();
        String injected = browser.get("/auth/login?error=%3Cscript%3Ealert(1)%3C/script%3E").body();

        assertThat(wrong).contains("role=\"alert\"").contains("Неправильний логін або пароль.")
                .contains("aria-invalid=\"true\"").contains("aria-describedby=\"auth-message\"");
        // Google ще не налаштовано: без підказки про нього
        assertThat(throttled).contains("Забагато спроб. Спробуйте пізніше.").doesNotContain("через Google");
        assertThat(injected).contains("Неправильний логін або пароль.").doesNotContain("alert(1)");
    }

    @Test
    void staticScriptsAreServedAndTheRootGoesToTheSignInPage() {
        var browser = browsers.open();

        assertThat(browser.get("/auth/ui/theme.js").statusCode()).isEqualTo(200);
        assertThat(browser.get("/auth/ui/login.js").statusCode()).isEqualTo(200);
        assertThat(browser.get("/auth/").headers().firstValue("Location")).hasValue("/auth/login");
    }

    @Test
    void anUnknownPathShowsTheUkrainianErrorPageNotTheWhitelabel() {
        var browser = browsers.open();
        // невідомий клієнт authorize: 400 зі сторінкою помилки
        HttpResponse<String> response = browser.get("/auth/oauth2/authorize?response_type=code&client_id=nope"
                + "&redirect_uri=http%3A%2F%2Fevil.example%2F&scope=openid", "Accept", "text/html");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Не вдалося продовжити вхід").doesNotContain("Whitelabel");
    }
}
