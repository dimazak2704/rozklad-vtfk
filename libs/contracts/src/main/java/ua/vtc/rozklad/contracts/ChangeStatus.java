package ua.vtc.rozklad.contracts;

/** Lifecycle of a change, see DOMAIN.md §7. */
public enum ChangeStatus {
    DRAFT,
    PENDING_CONFIRMATION,
    CONFIRMED,
    DECLINED,
    CANCELLED,
    APPLIED
}
