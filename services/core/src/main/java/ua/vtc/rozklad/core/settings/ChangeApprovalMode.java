package ua.vtc.rozklad.core.settings;

/**
 * Setting {@code approvalMode} (DOMAIN §7): what happens when the dispatcher assigns a teacher.
 * NOTIFY (default) confirms the change at once and informs the teacher, who may answer «Добре» or
 * «Не можу»; CONFIRM waits for the teacher's confirmation (Telegram or the cabinet); AUTO confirms at
 * once without a message (the teacher learns from the published order). The substitution module maps
 * it to its own {@code ApprovalMode} column type.
 */
public enum ChangeApprovalMode {
    AUTO,
    CONFIRM,
    NOTIFY
}
