package ua.vtc.rozklad.notification.delivery;

/** Why a message was queued (delivery log filter; values match {@code ck_outbound_message_purpose}). */
public enum DeliveryPurpose {
    ORDER_GROUP,
    ORDER_TEACHER,
    ORDER_ADMIN,
    SUBSTITUTION_REQUEST,
    /** NOTIFY: «Вам поставили заміну» with [👍 Добре] [❌ Не можу]. */
    SUBSTITUTION_NOTICE,
    SUBSTITUTION_REMINDER,
    SUBSTITUTION_STATUS,
    SUBSTITUTION_ADMIN,
    ABSENCE_ADMIN,
    GENERATION_ADMIN,
    DIGEST,
    TEST;

    /** Messages with answer buttons whose ids must be remembered for later edits. */
    public boolean isSubstitutionRequest() {
        return this == SUBSTITUTION_REQUEST || this == SUBSTITUTION_NOTICE || this == SUBSTITUTION_REMINDER;
    }
}
