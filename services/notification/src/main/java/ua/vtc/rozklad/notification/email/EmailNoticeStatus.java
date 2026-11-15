package ua.vtc.rozklad.notification.email;

/** PENDING → EXPANDED (letters written, or nobody to write to) | RETRYING (core unreachable) → … | FAILED. */
public enum EmailNoticeStatus {
    PENDING,
    RETRYING,
    EXPANDED,
    FAILED
}
