package ua.vtc.rozklad.auth.account;

import java.util.Set;

/** Human roles stored in {@code account_role}; service roles live in client configuration. */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final Set<String> HUMAN = Set.of(ADMIN, TEACHER);

    private Roles() {
    }
}
