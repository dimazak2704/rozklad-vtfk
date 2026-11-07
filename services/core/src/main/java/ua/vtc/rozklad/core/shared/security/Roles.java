package ua.vtc.rozklad.core.shared.security;

/** Keycloak realm roles ({@code realm_access.roles}); Spring authorities are {@code ROLE_<name>}. */
public final class Roles {

    /** Dispatcher / deputy director. */
    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";
    /** Service accounts of notification and importer (client credentials). */
    public static final String SERVICE = "SERVICE";

    private Roles() {
    }
}
