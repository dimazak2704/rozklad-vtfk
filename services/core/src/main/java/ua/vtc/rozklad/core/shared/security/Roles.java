package ua.vtc.rozklad.core.shared.security;

/** Roles of the access token ({@link TokenRoles}); Spring authorities are {@code ROLE_<name>}. */
public final class Roles {

    /** Dispatcher / deputy director. */
    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";
    /**
     * Any service account (client credentials). Not enough for core's internal and import API: each
     * service also carries its own role below and core checks that one (least privilege).
     */
    public static final String SERVICE = "SERVICE";
    /** The Telegram bot (notification): teacher replies, absences, teacher lookup. */
    public static final String SERVICE_NOTIFICATION = "SERVICE_NOTIFICATION";
    /** The importer: catalog snapshot and {@code /api/admin/import/**}. */
    public static final String SERVICE_IMPORTER = "SERVICE_IMPORTER";

    private Roles() {
    }
}
