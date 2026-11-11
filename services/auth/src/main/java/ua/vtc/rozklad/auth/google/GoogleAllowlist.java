package ua.vtc.rozklad.auth.google;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.FederatedIdentities;
import ua.vtc.rozklad.auth.config.GoogleProperties;
import ua.vtc.rozklad.auth.login.LoginAttempts.Outcome;

/**
 * Who may sign in with Google (DESIGN §4.4). The access list is the set of enabled accounts: an account
 * created in «Доступи» (or by an invite) with this e-mail. Being on the college domain alone never grants
 * access, so a student with a {@code vtc.vn.ua} address is refused unless an admin added that address.
 *
 * <ol>
 *   <li>{@code email_verified} must be true;</li>
 *   <li>the domain rule ({@link #domainAllows});</li>
 *   <li>an existing binding by {@code sub} decides (an e-mail change at Google keeps working);</li>
 *   <li>otherwise the account with this e-mail is bound on its first Google sign-in, unless it is already
 *       bound to another Google account (a recreated Workspace account: an admin unbinds first).</li>
 * </ol>
 */
@Component
public class GoogleAllowlist {

    private final AccountRepository accounts;
    private final FederatedIdentities bindings;
    private final GoogleProperties properties;

    public GoogleAllowlist(AccountRepository accounts, FederatedIdentities bindings, GoogleProperties properties) {
        this.accounts = accounts;
        this.bindings = bindings;
        this.properties = properties;
    }

    /** The decision for one Google sign-in; a success binds the account and records the sign-in time. */
    @Transactional
    public Decision decide(GoogleIdentity identity, Instant now) {
        if (!identity.emailVerified() || identity.email() == null) {
            return Decision.refused(Outcome.EMAIL_UNVERIFIED, null);
        }
        if (!domainAllows(identity, properties.domain(), properties.allowConsumerAccounts())) {
            return Decision.refused(Outcome.DOMAIN_MISMATCH, null);
        }
        Optional<UUID> bound = bindings.accountOf(identity.subject());
        if (bound.isPresent()) {
            return signIn(accounts.findById(bound.get()).orElse(null), identity, now, Outcome.DISABLED);
        }
        Optional<Account> byEmail = accounts.findByEmail(identity.email());
        if (byEmail.isEmpty() || !byEmail.get().enabled()) {
            return Decision.refused(Outcome.NOT_ALLOWED, byEmail.map(Account::id).orElse(null));
        }
        Account account = byEmail.get();
        if (bindings.subjectOf(account.id()).isPresent()) {
            return Decision.refused(Outcome.IDENTITY_CONFLICT, account.id());
        }
        if (!bindings.bind(account.id(), identity.subject(), identity.email(), identity.hostedDomain(), now)) {
            // одночасний перший вхід: перечитуємо прив'язку замість помилки унікальності
            Optional<UUID> winner = bindings.accountOf(identity.subject());
            if (winner.isEmpty() || !winner.get().equals(account.id())) {
                return Decision.refused(Outcome.IDENTITY_CONFLICT, account.id());
            }
        }
        return signIn(account, identity, now, Outcome.NOT_ALLOWED);
    }

    private Decision signIn(Account account, GoogleIdentity identity, Instant now, Outcome whenDisabled) {
        if (account == null) {
            return Decision.refused(Outcome.NOT_ALLOWED, null);
        }
        if (!account.enabled()) {
            return Decision.refused(account.revoked() ? Outcome.REVOKED : whenDisabled, account.id());
        }
        bindings.recordLogin(identity.subject(), now);
        accounts.recordLogin(account.id(), now);
        return new Decision(Outcome.SUCCESS, account);
    }

    /**
     * The domain rule. With a configured domain, an address on that domain must come with the same {@code hd}
     * (a consumer Google account registered on a college address has none and is refused); an address on any
     * other domain passes only with {@code allowConsumerAccounts}. Without a configured domain there is no rule
     * (local testing; production refuses that unless consumer accounts are allowed explicitly).
     */
    static boolean domainAllows(GoogleIdentity identity, String domain, boolean allowConsumerAccounts) {
        if (domain == null) {
            return true;
        }
        if (domain.equals(identity.emailDomain())) {
            return domain.equals(identity.hostedDomain());
        }
        return allowConsumerAccounts;
    }

    /**
     * @param outcome  {@code SUCCESS} or the refusal, as it goes to {@code login_attempt}
     * @param account  the signed-in account (success only)
     * @param accountId the account the refusal concerns, if any (journal only)
     */
    public record Decision(Outcome outcome, Account account, UUID accountId) {

        Decision(Outcome outcome, Account account) {
            this(outcome, account, account.id());
        }

        static Decision refused(Outcome outcome, UUID accountId) {
            return new Decision(outcome, null, accountId);
        }

        public boolean allowed() {
            return outcome == Outcome.SUCCESS;
        }
    }
}
