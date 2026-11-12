package ua.vtc.rozklad.auth.seed;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.PasswordPolicy;
import ua.vtc.rozklad.auth.account.Roles;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Demo teachers for the defense on the server ({@code krentsin}, {@code nepyivoda}; DESIGN §4.10). With
 * {@code DEMO_TEACHER_PASSWORD} set, every start creates the missing ones with the fixed ids of the demo links
 * (marked {@code demo}), sets that password and turns them back on after {@code DEMO_OFF}. With it empty, every
 * {@code demo} account is disabled with {@code DEMO_OFF} and signed out. Accounts that are not {@code demo}
 * are never touched, a revoked demo account stays revoked, and admins are not seeded: the owner signs in
 * as the bootstrap admin. Runs with an https issuer only; the local stand has {@link DemoSeed}.
 */
@Component
public class ServerDemoAccounts implements ApplicationRunner, Ordered {

    static final String ACTOR = "demo-accounts";
    static final String DEMO_OFF = "DEMO_OFF";
    private static final Logger log = LoggerFactory.getLogger(ServerDemoAccounts.class);

    private final AuthServerProperties properties;
    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ServerDemoAccounts(AuthServerProperties properties, AccountRepository accounts, PasswordPolicy policy,
            PasswordEncoder passwordEncoder, TransactionTemplate transactions, Clock clock) {
        this.properties = properties;
        this.accounts = accounts;
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        // між демо-акаунтами стенда (10) і bootstrap (20)
        return 15;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.secure()) {
            apply(properties.demoSeed().serverTeacherPassword());
        }
    }

    /** Brings the demo teachers in line with {@code password}; empty means off. */
    void apply(String password) {
        if (!StringUtils.hasText(password)) {
            transactions.executeWithoutResult(status -> turnOff());
            return;
        }
        for (AuthServerProperties.DemoAccount demo : properties.demoSeed().accounts()) {
            if (!Roles.TEACHER.equals(demo.role())) {
                continue;
            }
            policy.check(password, demo.username(), demo.email()).ifPresent(reason -> {
                throw new IllegalStateException("DEMO_TEACHER_PASSWORD is rejected by the password policy: "
                        + reason.code() + " (at least " + policy.minLength() + " characters)");
            });
            transactions.executeWithoutResult(status -> turnOn(demo, password));
        }
    }

    private void turnOn(AuthServerProperties.DemoAccount demo, String password) {
        Instant now = clock.instant();
        Optional<Account> existing = accounts.findById(demo.id());
        if (existing.isEmpty()) {
            if (accounts.findByUsername(demo.username()).isPresent()
                    || (demo.email() != null && accounts.findByEmail(demo.email()).isPresent())) {
                log.warn("demo account {}: the username or e-mail belongs to another account, skipped",
                        demo.username());
                return;
            }
            accounts.insert(new AccountRepository.NewAccount(demo.id(), demo.username(), demo.email(),
                    demo.displayName(), Set.of(Roles.TEACHER), passwordEncoder.encode(password), true), now, ACTOR);
            log.info("demo account {} created", demo.username());
            return;
        }
        Account account = existing.get();
        if (!account.demo()) {
            log.warn("demo account {}: the id belongs to an account that is not a demo one, left as it is",
                    demo.username());
            return;
        }
        if (account.revoked()) {
            log.info("demo account {} is revoked and stays off", demo.username());
            return;
        }
        if (!account.hasPassword() || !passwordEncoder.matches(password, account.passwordHash())) {
            accounts.setPassword(account.id(), passwordEncoder.encode(password), now, ACTOR);
        }
        if (!account.enabled() && DEMO_OFF.equals(account.disabledReason())) {
            accounts.enableClearingReason(account.id(), now, ACTOR);
            log.info("demo account {} turned on again", demo.username());
        }
    }

    private void turnOff() {
        Instant now = clock.instant();
        for (Account account : accounts.findDemo()) {
            if (account.enabled()) {
                accounts.disable(account.id(), DEMO_OFF, now, ACTOR);
                accounts.deleteSignIns(account.id());
                log.info("demo account {} turned off (DEMO_TEACHER_PASSWORD is empty)", account.loginName());
            }
        }
    }
}
