package ua.vtc.rozklad.core.substitution.internal.options;

/**
 * Coarse quality of an option, compared before its cost (DOMAIN §7): {@code CLEAN} — a cover or
 * swap without a warning; {@code EASY_CANCEL} — dropping the cohort's first, last or only pair when
 * the lesson is online, the group has a block or the discipline's hours suffice; {@code CAUTION} —
 * a cover or swap with a warning; {@code LAST} — any other cancel (the group gets a window).
 */
public enum Band {
    CLEAN,
    EASY_CANCEL,
    CAUTION,
    LAST
}
