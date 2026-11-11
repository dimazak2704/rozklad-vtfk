package ua.vtc.rozklad.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.auth.account.AccountRuleException.Rule;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * The usable-admin invariant (D12) of {@link AccountAdministration}. Each case runs in a transaction that is
 * rolled back: it first disables every other admin of the shared test database, so the target is the last one.
 */
@AuthIntegrationTest
class LastAdminIT {

    @Autowired
    private AccountAdministration administration;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void theLastUsableAdminCannotBeRevokedDeletedOrDemoted() {
        UUID admin = accounts.admin().id();

        asLastAdmin(admin, id -> refused(() -> administration.revoke(id, "test")));
        asLastAdmin(admin, id -> refused(() -> administration.delete(id, "test")));
        asLastAdmin(admin, id -> refused(() -> administration.update(id,
                new AccountAdministration.AccountPatch(null, null, null, Set.of(Roles.TEACHER)), "test")));
    }

    @Test
    void googleIsTheOnlyCredentialOfTheLastAdmin() {
        UUID admin = accounts.admin().id();
        asLastAdmin(admin, id -> {
            jdbc.sql("UPDATE account SET password_hash = NULL WHERE id = ?").param(id).update();
            jdbc.sql("""
                    INSERT INTO federated_identity (id, account_id, provider, subject, email_at_link, linked_at)
                    VALUES (?, ?, 'google', ?, 'a@b.c', now())
                    """).params(UUID.randomUUID(), id, "g-" + id).update();
            refused(() -> administration.unbindGoogle(id, "test"));
        });
        // з паролем відв'язати Google можна і останньому адміністраторові
        asLastAdmin(admin, id -> administration.unbindGoogle(id, "test"));
    }

    @Test
    void anAdminWithoutAnyCredentialIsNotTheLastAdmin() {
        UUID admin = accounts.admin().id();
        asLastAdmin(admin, id -> {
            UUID typo = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO account (id, email, display_name, enabled, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, 'Без входу', true, now(), 'test', now(), 'test')
                    """).params(typo, "typo-" + typo + "@example.test").update();
            jdbc.sql("INSERT INTO account_role (account_id, role) VALUES (?, 'ADMIN')").param(typo).update();
            // адміністратор без пароля й Google не рахується, тож відкликати його можна, а справжнього — ні
            administration.revoke(typo, "test");
            refused(() -> administration.revoke(id, "test"));
        });
    }

    @Test
    void anotherUsableAdminAllowsTheChange() {
        UUID first = accounts.admin().id();
        UUID second = accounts.admin().id();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("UPDATE account SET enabled = false WHERE id NOT IN (?, ?) AND id IN "
                    + "(SELECT account_id FROM account_role WHERE role = 'ADMIN')").params(first, second).update();
            administration.revoke(first, "test");
            assertThat(jdbc.sql("SELECT disabled_reason FROM account WHERE id = ?").param(first).query(String.class)
                    .single()).isEqualTo(Account.REVOKED);
            refused(() -> administration.revoke(second, "test"));
            status.setRollbackOnly();
        });
    }

    private void asLastAdmin(UUID admin, Consumer<UUID> check) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("UPDATE account SET enabled = false WHERE id <> ? AND id IN "
                    + "(SELECT account_id FROM account_role WHERE role = 'ADMIN')").param(admin).update();
            check.accept(admin);
            status.setRollbackOnly();
        });
    }

    private static void refused(Runnable change) {
        assertThatThrownBy(change::run).isInstanceOfSatisfying(AccountRuleException.class,
                refused -> assertThat(refused.rule()).isEqualTo(Rule.LAST_ADMIN));
    }
}
