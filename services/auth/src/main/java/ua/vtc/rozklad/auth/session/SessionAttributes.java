package ua.vtc.rozklad.auth.session;

/** Names of our own session attributes. */
public final class SessionAttributes {

    /** Epoch milliseconds of the sign-in; the absolute session lifetime counts from it. */
    public static final String AUTHENTICATED_AT = "auth.authenticatedAt";

    private SessionAttributes() {
    }
}
