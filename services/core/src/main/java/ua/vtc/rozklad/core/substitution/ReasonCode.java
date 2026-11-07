package ua.vtc.rozklad.core.substitution;

/**
 * Reason printed in the order's «Причина заміни» column (DOMAIN §7). Sick leave and other private
 * absence categories are printed as «Відп.»; OTHER uses the change's free {@code reasonText}.
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
}
