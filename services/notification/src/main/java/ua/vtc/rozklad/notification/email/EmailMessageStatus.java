package ua.vtc.rozklad.notification.email;

/**
 * QUEUED → SENT | RETRYING → … → SENT | FAILED; SUPERSEDED: a newer letter of the same change replaced it before
 * it was sent.
 */
public enum EmailMessageStatus {
    QUEUED,
    RETRYING,
    SENT,
    FAILED,
    SUPERSEDED;

    public boolean isPending() {
        return this == QUEUED || this == RETRYING;
    }
}
