package ua.vtc.rozklad.auth.seed;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * The first admin and the break-glass repair (DESIGN §4.10). Runs only while no usable admin exists (an
 * enabled ADMIN with a password or a Google binding), so it is naturally one-shot; with
 * {@code AUTH_BOOTSTRAP_FORCE=1} it repairs the named account even when other admins exist. A matching
 * account (by username or e-mail) gets the password, the ADMIN role and is enabled, revocation included, and
 * loses its existing sign-in sessions and authorizations; otherwise a new admin is created. The password must pass {@link PasswordPolicy}.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner, Ordered {

    static final String ACTOR = "bootstrap";
    private static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9._-]{2,49}$");
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AuthServerProperties.Bootstrap config;
    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BootstrapAdmin(AuthServerProperties properties, AccountRepository accounts, PasswordPolicy policy,
            PasswordEncoder passwordEncoder, TransactionTemplate transactions, Clock clock) {
        this.config = properties.bootstrap();
        this.accounts = accounts;
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        // після демо-акаунтів: якщо стенд уже має адміністратора, нічого не робимо
        return 20;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!config.configured()) {
            if (accounts.countUsableAdmins() == 0) {
                log.warn("no usable admin account: set AUTH_BOOTSTRAP_ADMIN_USERNAME (or _EMAIL) and "
                        + "AUTH_BOOTSTRAP_ADMIN_PASSWORD and restart auth");
            }
            return;
        }
        transactions.executeWithoutResult(status -> apply());
    }

    void apply() {
        accounts.lockAdminInvariant();
        if (accounts.countUsableAdmins() > 0 && !config.force()) {
            return;
        }
        String username = blankToNull(config.username());
        String email = blankToNull(config.email());
        if (username != null && !USERNAME.matcher(username.toLowerCase(java.util.Locale.ROOT)).matches()) {
            throw new IllegalStateException("AUTH_BOOTSTRAP_ADMIN_USERNAME must match " + USERNAME.pattern());
        }
        policy.check(config.password(), username, email).ifPresent(reason -> {
            throw new IllegalStateException("AUTH_BOOTSTRAP_ADMIN_PASSWORD is rejected by the password policy: "
                    + reason.code() + " (at least " + policy.minLength() + " characters)");
        });
        Instant now = clock.instant();
        String hash = passwordEncoder.encode(config.password());
        Optional<Account> existing = Optional.ofNullable(username).flatMap(accounts::findByUsername)
                .or(() -> Optional.ofNullable(email).flatMap(accounts::findByEmail));
        if (existing.isPresent()) {
            UUID id = existing.get().id();
            accounts.setPassword(id, hash, now, ACTOR);
            accounts.addRole(id, Roles.ADMIN);
            accounts.enableClearingReason(id, now, ACTOR);
            // новий пароль: сесії й авторизації зі старим паролем більше не діють
            accounts.deleteSignIns(id);
            log.warn("bootstrap: account {} repaired as an admin with a new password; remove "
                    + "AUTH_BOOTSTRAP_ADMIN_PASSWORD{} after the first sign-in", id,
                    config.force() ? " and AUTH_BOOTSTRAP_FORCE" : "");
        } else {
            UUID id = UUID.randomUUID();
            accounts.insert(new AccountRepository.NewAccount(id, username, email, "Адміністратор",
                    Set.of(Roles.ADMIN), hash, false), now, ACTOR);
            log.warn("bootstrap: admin account {} created; remove AUTH_BOOTSTRAP_ADMIN_PASSWORD after the first "
                    + "sign-in", id);
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
