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
        String username = uniqueName();
        return insert(role, username, username + "@test.local", passwordEncoder.encode(PASSWORD));
    }

    /** An access grant from «Доступи»: e-mail and role, no username and no password (Google only). */
    public Created grant(String role, String email) {
        return insert(role, null, email, null);
    }

    /** A unique address on {@code domain}. */
    public static String email(String domain) {
        return uniqueName() + "@" + domain;
    }

    private Created insert(String role, String username, String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        String name = username != null ? username : email;
        accounts.insert(new AccountRepository.NewAccount(id, username, email, "Тест " + name, Set.of(role),
                passwordHash, false), Instant.now(), "test");
        return new Created(id, username, email);
    }

    private static String uniqueName() {
        return "user" + SEQUENCE.incrementAndGet() + "." + Long.toString(System.nanoTime(), 36);
    }

    public record Created(UUID id, String username, String email) {
    }
}
