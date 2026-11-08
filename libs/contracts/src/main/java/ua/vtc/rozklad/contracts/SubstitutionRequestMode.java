package ua.vtc.rozklad.contracts;

/**
 * How a substitution reaches the assigned teacher ({@code substitution.confirmation-requested.mode}, DOMAIN §7).
 * CONFIRM: a request the teacher must confirm before the change counts. NOTIFY: the change is already
 * confirmed; the teacher is informed and may answer «Добре» or «Не можу». A missing value (events of
 * older producers) means CONFIRM.
 */
public enum SubstitutionRequestMode {
    CONFIRM,
    NOTIFY
}
