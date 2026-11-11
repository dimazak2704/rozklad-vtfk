package ua.vtc.rozklad.auth.support;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import ua.vtc.rozklad.auth.account.AccountRepository;

/** Creates accounts with unique usernames for one test. */
public class TestAccounts {

    public static final String PASSWORD = "Correct-Horse-Battery-7";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    TestAccounts(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    public Created teacher() {
        return create("TEACHER");
    }

    public Created admin() {
        return create("ADMIN");
    }

    public Created create(String role) {
        UUID id = UUID.randomUUID();
        String username = "user" + SEQUENCE.incrementAndGet() + "." + Long.toString(System.nanoTime(), 36);
        String email = username + "@test.local";
        accounts.insert(new AccountRepository.NewAccount(id, username, email, "Тест " + username, Set.of(role),
                passwordEncoder.encode(PASSWORD), false), Instant.now(), "test");
        return new Created(id, username, email);
    }

    public record Created(UUID id, String username, String email) {
    }
}
