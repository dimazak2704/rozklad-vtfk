package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * Lockout per identifier and network, the global slowdown and the network throttle (DESIGN §4.3). The test
 * configuration lowers the thresholds: 3 failures, 8 for the slowdown (600 ms), 6 identifiers per network.
 * Client addresses come through {@code X-Forwarded-For}, as from nginx.
 */
@AuthIntegrationTest
class LockoutIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anIdentifierIsLockedOnlyFromTheNetworkThatFailed() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser attacker = browsers.open("198.51.100.7");
        for (int i = 0; i < 3; i++) {
            assertThat(Browser.location(attacker.signIn(teacher.username(), "wrong-" + i))).isEqualTo("/auth/login?error");
        }

        // навіть правильний пароль з тієї ж мережі не перевіряється
        HttpResponse<String> locked = attacker.signIn(teacher.username(), TestAccounts.PASSWORD);
        assertThat(Browser.location(locked)).isEqualTo("/auth/login?error=throttled");
        assertThat(attacker.get("/auth/login?error=throttled").body())
                .contains("Забагато спроб. Спробуйте пізніше.");

        // інша мережа (сусідня адреса в тій самій /24 — та сама мережа)
        assertThat(Browser.location(browsers.open("198.51.100.99").signIn(teacher.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error=throttled");
        assertThat(Browser.location(browsers.open("192.0.2.50").signIn(teacher.username(), TestAccounts.PASSWORD)))
                .doesNotContain("error");
    }

    @Test
    void theLockEndsAfterTheDelay() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open("198.51.101.7");
        for (int i = 0; i < 3; i++) {
            browser.signIn(teacher.username(), "wrong-" + i);
        }
        assertThat(Browser.location(browser.signIn(teacher.username(), TestAccounts.PASSWORD))).contains("throttled");

        // остання невдача була 61 с тому: базова затримка 60 с минула
        jdbc.sql("UPDATE login_attempt SET occurred_at = occurred_at - interval '61 seconds' WHERE account_id = ?")
                .param(teacher.id()).update();

        assertThat(Browser.location(browser.signIn(teacher.username(), TestAccounts.PASSWORD))).doesNotContain("error");
    }

    @Test
    void unknownIdentifiersAreLockedTheSameWay() {
        Browser browser = browsers.open("198.51.102.7");
        String ghost = "ghost-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            assertThat(Browser.location(browser.signIn(ghost, "wrong-" + i))).isEqualTo("/auth/login?error");
        }
        assertThat(Browser.location(browser.signIn(ghost, "wrong-x"))).isEqualTo("/auth/login?error=throttled");
    }

    @Test
    void manyFailuresFromEverywhereSlowAnIdentifierDownButNeverBlockIt() {
        TestAccounts.Created teacher = accounts.teacher();
        byte[] hash = LoginAttempts.hash(teacher.username());
        Instant recent = Instant.now().minus(Duration.ofMinutes(30));
        // 8 невдач із 8 різних мереж (по одній, тож блокування на ключ не спрацьовує)
        for (int i = 0; i < 8; i++) {
            jdbc.sql("""
                    INSERT INTO login_attempt (occurred_at, method, identifier_hash, ip, ip_prefix, outcome)
                    VALUES (?, 'PASSWORD', ?, ?::inet, ?::cidr, 'BAD_CREDENTIALS')
                    """).params(Timestamp.from(recent), hash, "10.9." + i + ".1", "10.9." + i + ".0/24").update();
        }

        long started = System.nanoTime();
        HttpResponse<String> response = browsers.open("192.0.2.77").signIn(teacher.username(), TestAccounts.PASSWORD);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(Browser.location(response)).doesNotContain("error");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(600);
    }

    @Test
    void sprayingManyIdentifiersThrottlesTheWholeNetwork() {
        Browser sprayer = browsers.open("198.51.103.9");
        for (int i = 0; i < 6; i++) {
            sprayer.signIn("spray" + i + "-" + System.nanoTime(), "Password123!");
        }
        TestAccounts.Created student = accounts.teacher();

        // той самий NAT, інший комп'ютер і правильний пароль
        HttpResponse<String> neighbour = browsers.open("198.51.103.200").signIn(student.username(), TestAccounts.PASSWORD);

        assertThat(Browser.location(neighbour)).isEqualTo("/auth/login?error=throttled");
        assertThat(jdbc.sql("SELECT count(*) FROM login_attempt WHERE ip_prefix = '198.51.103.0/24' AND outcome = 'THROTTLED'")
                .query(Long.class).single()).isPositive();
        // інша мережа не зачеплена
        assertThat(Browser.location(browsers.open("192.0.2.90").signIn(student.username(), TestAccounts.PASSWORD)))
                .doesNotContain("error");
    }
}
