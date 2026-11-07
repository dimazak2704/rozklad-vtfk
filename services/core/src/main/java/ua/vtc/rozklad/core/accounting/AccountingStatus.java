package ua.vtc.rozklad.core.accounting;

/**
 * Status of a discipline of a group (DOMAIN §9): FINISHED when the fact reached the plan,
 * WILL_OVERRUN / WILL_UNDERRUN when the forecast misses the plan by more than one pair (2 hours).
 */
public enum AccountingStatus {
    FINISHED,
    WILL_OVERRUN,
    WILL_UNDERRUN,
    ON_TRACK
}
