package ua.vtc.rozklad.core.shared.domain;

import ua.vtc.rozklad.contracts.ChangeKind;

/**
 * Reason of a change (DOMAIN §7) and its abbreviation in the order's «Причина заміни» column. Shared
 * because the order (substitution module) and the public schedule (timetable module) print the same
 * text, and timetable must not depend on substitution.
 */
public enum ReasonCode {
    ABSENT("Відп."),
    MUTUAL("Зв.зам."),
    FINISHED("Вичит."),
    BUSINESS_TRIP("Відр."),
    OTHER(null);

    private final String orderText;

    ReasonCode(String orderText) {
        this.orderText = orderText;
    }

    /** Short text for the order, {@code null} for OTHER. */
    public String orderText() {
        return orderText;
    }

    /**
     * Reason as printed in orders and schedules: BLOCK, ADDITIONAL and room-only rows have none, a
     * finished discipline is always «Вичит.», OTHER prints the free text (may be empty), every other
     * code its abbreviation («Відп.», «Зв.зам.», «Відр.»).
     */
    public static String printed(ChangeKind kind, ReasonCode code, String freeText) {
        if (kind == ChangeKind.BLOCK || kind == ChangeKind.ADDITIONAL || kind == ChangeKind.ROOM_CHANGE) {
            return null;
        }
        if (kind == ChangeKind.DISCIPLINE_FINISHED) {
            return FINISHED.orderText();
        }
        if (code == null) {
            return null;
        }
        if (code == OTHER) {
            return freeText == null || freeText.isBlank() ? null : freeText.strip();
        }
        return code.orderText();
    }
}
