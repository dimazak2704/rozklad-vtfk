package ua.vtc.rozklad.notification.email;

/** Who a kind of letter is for, and so whose settings page offers it. */
public enum EmailAudience {
    /** The teacher of a substitution (an account linked to a teacher). */
    TEACHER,
    /** The dispatchers: enabled accounts with the role ADMIN. */
    ADMIN,
    /** Whoever asked for it (the test letter); never listed. */
    SELF
}
