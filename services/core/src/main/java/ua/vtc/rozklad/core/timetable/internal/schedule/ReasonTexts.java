package ua.vtc.rozklad.core.timetable.internal.schedule;

/**
 * Public reason of a change as printed in the order's «Причина заміни» column (DOMAIN.md §7). The
 * absence category never appears: a sick leave is «Відп.». Mirrors {@code substitution.ReasonCode}
 * by name, without a Java dependency on that module.
 */
final class ReasonTexts {

    private ReasonTexts() {
    }

    static String of(String reasonCode, String reasonText) {
        if (reasonCode == null) {
            return reasonText;
        }
        return switch (reasonCode) {
            case "ABSENT" -> "Відп.";
            case "MUTUAL" -> "Зв.зам.";
            case "FINISHED" -> "Вичит.";
            case "BUSINESS_TRIP" -> "Відр.";
            default -> reasonText;
        };
    }
}
