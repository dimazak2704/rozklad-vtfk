package ua.vtc.rozklad.auth.session;

/** Names of our own session attributes. */
public final class SessionAttributes {

    /** Epoch milliseconds of the sign-in; the absolute session lifetime counts from it. */
    public static final String AUTHENTICATED_AT = "auth.authenticatedAt";
    /**
     * Epoch milliseconds when this session last completed a Google sign-in (set by the Google success handler
     * only, never from anything the client sends); with {@link #GOOGLE_ACCOUNT_ID} the proof of a fresh Google
     * re-authentication ({@link GoogleSignInMark}).
     */
    public static final String GOOGLE_AUTHENTICATED_AT = "auth.googleAuthenticatedAt";
    /** The account id (UUID string) that Google sign-in signed this session in as. */
    public static final String GOOGLE_ACCOUNT_ID = "auth.googleAccountId";

    private SessionAttributes() {
    }
}
