package ua.vtc.rozklad.auth.account;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness component {@code adminCredential}: DOWN while no usable admin exists (nobody could manage
 * access). The deploy gate checks it; the bootstrap admin fixes it.
 */
@Component
public class AdminCredentialHealthIndicator implements HealthIndicator {

    private final AccountRepository accounts;

    public AdminCredentialHealthIndicator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Health health() {
        return accounts.countUsableAdmins() > 0 ? Health.up().build()
                : Health.down().withDetail("reason", "no usable admin account").build();
    }
}
