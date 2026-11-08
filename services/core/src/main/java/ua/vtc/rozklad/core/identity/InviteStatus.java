package ua.vtc.rozklad.core.identity;

/** State of an invite at a moment: ACTIVE can be accepted; the others answer 410 Gone. */
public enum InviteStatus {
    ACTIVE,
    USED,
    EXPIRED,
    REVOKED
}
