package ua.vtc.rozklad.auth.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.PasswordPolicy;
import ua.vtc.rozklad.auth.config.AuthServerProperties.DemoAccount;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

/**
 * Demo teachers for the defense on the server: created and switched by {@code DEMO_TEACHER_PASSWORD}, never
 * touching real or revoked accounts. Each test uses its own ids and names in the shared database.
 */
@AuthIntegrationTest
class ServerDemoAccountsIT {

    private static final String PASSWORD = "demo-pass-2026-x";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordPolicy policy;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    private DemoAccount teacher;
    private DemoAccount admin;
    private ServerDemoAccounts seed;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime(), 36);
        teacher = new DemoAccount(UUID.randomUUID(), "demo-t" + suffix, "demo-t" + suffix + "@rozklad.local",
                "Демо викладач", "TEACHER", null);
        admin = new DemoAccount(UUID.randomUUID(), "demo-a" + suffix, "demo-a" + suffix + "@rozklad.local",
                "Демо адміністратор", "ADMIN", null);
        seed = new ServerDemoAccounts(PropertiesFixture.prod().demoAccounts(List.of(teacher, admin)).build(),
                accounts, policy, passwordEncoder, transactions, Clock.systemUTC());
    }

    @Test
    void createsOnlyTheTeachersAsDemoAccountsWithThePassword() {
        seed.apply(PASSWORD);

        Account created = accounts.findById(teacher.id()).orElseThrow();
        assertThat(created.demo()).isTrue();
        assertThat(created.enabled()).isTrue();
        assertThat(created.roles()).containsExactly("TEACHER");
        assertThat(passwordEncoder.matches(PASSWORD, created.passwordHash())).isTrue();
        assertThat(accounts.findById(admin.id())).isEmpty();
    }

    @Test
    void emptyPasswordTurnsDemoAccountsOffAndSignsThemOutAndANewPasswordTurnsThemOnAgain() {
        seed.apply(PASSWORD);
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
                                            max_inactive_interval, expiry_time, principal_name)
                VALUES (?, ?, ?, ?, 1800, ?, ?)
                """).params(UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now, now + 1_800_000,
                        teacher.id().toString())
                .update();

        seed.apply("");

        Account off = accounts.findById(teacher.id()).orElseThrow();
        assertThat(off.enabled()).isFalse();
        assertThat(off.disabledReason()).isEqualTo("DEMO_OFF");
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                .param(teacher.id().toString()).query(Long.class).single()).isZero();

        seed.apply("another-demo-2026");

        Account on = accounts.findById(teacher.id()).orElseThrow();
        assertThat(on.enabled()).isTrue();
        assertThat(on.disabledReason()).isNull();
        assertThat(passwordEncoder.matches("another-demo-2026", on.passwordHash())).isTrue();
    }

    @Test
    void aRevokedDemoAccountStaysRevoked() {
        seed.apply(PASSWORD);
        accounts.disable(teacher.id(), Account.REVOKED, Instant.now(), "test");

        seed.apply(PASSWORD);

        assertThat(accounts.findById(teacher.id()).orElseThrow().revoked()).isTrue();
    }

    @Test
    void anAccountThatIsNotADemoOneIsLeftAsItIs() {
        accounts.insert(new AccountRepository.NewAccount(teacher.id(), teacher.username(), teacher.email(),
                "Справжній викладач", Set.of("TEACHER"), passwordEncoder.encode("Real-Password-2026"), false),
                Instant.now(), "test");

        seed.apply(PASSWORD);
        seed.apply("");

        Account real = accounts.findById(teacher.id()).orElseThrow();
        assertThat(real.enabled()).isTrue();
        assertThat(real.displayName()).isEqualTo("Справжній викладач");
        assertThat(passwordEncoder.matches("Real-Password-2026", real.passwordHash())).isTrue();
    }

    @Test
    void aWeakPasswordStopsTheStart() {
        assertThatThrownBy(() -> seed.apply("short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_TEACHER_PASSWORD");
        assertThat(accounts.findById(teacher.id())).isEmpty();
    }
}
