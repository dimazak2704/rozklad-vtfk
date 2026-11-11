package ua.vtc.rozklad.auth.seed;

import java.time.Clock;
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
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.Roles;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Demo accounts of the local stand ({@code admin}, {@code krentsin}, {@code nepyivoda}) with the fixed ids
 * core's demo links use. Creates missing accounts only and never touches existing rows. Development only:
 * an https issuer refuses it ({@code ProdGuard}), and the passwords come from {@code infra/dev/demo-users.env}
 * (short demo passwords, so the length rule is not applied here).
 */
@Component
public class DemoSeed implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);

    private final AuthServerProperties properties;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DemoSeed(AuthServerProperties properties, AccountRepository accounts, PasswordEncoder passwordEncoder,
            TransactionTemplate transactions, Clock clock) {
        this.properties = properties;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.demoSeed().enabled() || properties.secure()) {
            return;
        }
        for (AuthServerProperties.DemoAccount demo : properties.demoSeed().accounts()) {
            transactions.executeWithoutResult(status -> seed(demo));
        }
    }

    private void seed(AuthServerProperties.DemoAccount demo) {
        if (!Roles.HUMAN.contains(demo.role())) {
            log.warn("demo account {}: unknown role {}, skipped", demo.username(), demo.role());
            return;
        }
        if (accounts.findById(demo.id()).isPresent()) {
            return;
        }
        if (accounts.findByUsername(demo.username()).isPresent()
                || (demo.email() != null && accounts.findByEmail(demo.email()).isPresent())) {
            log.warn("demo account {}: the username or e-mail belongs to another account, skipped", demo.username());
            return;
        }
        if (!StringUtils.hasText(demo.password())) {
            log.warn("demo account {}: no password in the environment (infra/dev/demo-users.env), skipped",
                    demo.username());
            return;
        }
        accounts.insert(new AccountRepository.NewAccount(demo.id(), demo.username(), demo.email(), demo.displayName(),
                Set.of(demo.role()), passwordEncoder.encode(demo.password()), false), clock.instant(), "demo-seed");
        log.info("demo account {} created ({})", demo.username(), demo.role());
    }
}
