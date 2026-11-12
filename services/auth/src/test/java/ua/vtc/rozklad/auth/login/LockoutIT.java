package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * Lockout per identifier and network, the global gate, the network throttle and trusted devices (DESIGN §4.3).
 * The test configuration lowers the thresholds: 3 failures, 8 for the gate (600 ms), 6 identifiers per network.
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
    void afterManyFailuresFromEverywhereOnlyOneParallelAttemptReachesThePasswordCheck() throws Exception {
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
        // 6 браузерів з 6 мереж відкривають форму, а потім надсилають її одночасно
        List<Browser> guessers = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Browser guesser = browsers.open("198.18." + (20 + i) + ".5");
            tokens.add(Browser.csrf(guesser.get("/auth/login", "Accept", "text/html").body()));
            guessers.add(guesser);
        }
        List<Callable<HttpResponse<String>>> posts = new ArrayList<>();
        for (int i = 0; i < guessers.size(); i++) {
            Browser guesser = guessers.get(i);
            Map<String, String> form = Map.of("username", teacher.username(), "password", "wrong-" + i, "_csrf",
                    tokens.get(i));
            posts.add(() -> guesser.postForm("/auth/login", form));
        }
        List<String> locations = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<HttpResponse<String>> post : executor.invokeAll(posts)) {
                locations.add(Browser.location(post.get()));
            }
        }

        assertThat(locations).filteredOn("/auth/login?error"::equals).hasSize(1);
        assertThat(locations).filteredOn("/auth/login?error=throttled"::equals).hasSize(5);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM login_attempt WHERE identifier_hash = ? AND outcome = 'BAD_CREDENTIALS'
                   AND occurred_at > ?
                """).params(hash, Timestamp.from(recent.plusSeconds(60))).query(Long.class).single()).isEqualTo(1);
        // після паузи ворота знову пропускають одну перевірку, і правильний пароль проходить
        Thread.sleep(700);
        assertThat(Browser.location(browsers.open("192.0.2.77").signIn(teacher.username(), TestAccounts.PASSWORD)))
                .doesNotContain("error");
    }

    @Test
    void aBrowserThatSignedInBeforeIsNotLockedOutByOthersOnTheSameNetwork() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser own = browsers.open("198.51.104.10");
        HttpResponse<String> first = own.signIn(teacher.username(), TestAccounts.PASSWORD);
        assertThat(Browser.location(first)).doesNotContain("error");
        assertThat(Browser.setCookies(first)).anySatisfy(cookie -> assertThat(cookie).startsWith("RZ_DEVICE=")
                .contains("Path=/auth/login").contains("HttpOnly").contains("SameSite=Strict"));
        own.removeCookie("RZ_AUTH");

        // той самий NAT: хтось перебирає логіни (мережу зачинено) і пароль цього викладача (ключ зачинено)
        Browser sprayer = browsers.open("198.51.104.200");
        for (int i = 0; i < 6; i++) {
            sprayer.signIn("spray" + i + "-" + System.nanoTime(), "Password123!");
        }
        Browser attacker = browsers.open("198.51.104.201");
        for (int i = 0; i < 3; i++) {
            attacker.signIn(teacher.username(), "wrong-" + i);
        }
        assertThat(Browser.location(browsers.open("198.51.104.50").signIn(teacher.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error=throttled");

        // браузер, з якого викладач уже входив, має власний лічильник
        assertThat(Browser.location(own.signIn(teacher.username(), TestAccounts.PASSWORD))).doesNotContain("error");
    }

    @Test
    void aTrustedBrowserIsLockedByItsOwnFailures() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser own = browsers.open("198.51.105.10");
        own.signIn(teacher.username(), TestAccounts.PASSWORD);
        own.removeCookie("RZ_AUTH");

        for (int i = 0; i < 3; i++) {
            assertThat(Browser.location(own.signIn(teacher.username(), "wrong-" + i))).isEqualTo("/auth/login?error");
        }

        assertThat(Browser.location(own.signIn(teacher.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error=throttled");
        // для іншого логіна кука нічого не дає: це звичайна спроба з цієї мережі
        assertThat(Browser.location(own.signIn("ghost-" + System.nanoTime(), "wrong"))).isEqualTo("/auth/login?error");
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
