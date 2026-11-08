package ua.vtc.rozklad.core.substitution;

/**
 * How a change with a new teacher was approved (DOMAIN §7). AUTO: confirmed at once (also every change
 * without a teacher to assign); CONFIRM: the new teacher confirms in Telegram or the cabinet; NOTIFY:
 * confirmed at once, the new teacher is informed and may answer «Добре» or «Не можу».
 */
public enum ApprovalMode {
    AUTO,
    CONFIRM,
    NOTIFY
}
