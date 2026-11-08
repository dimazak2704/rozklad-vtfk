package ua.vtc.rozklad.core.settings;

/**
 * Setting {@code approvalMode} (DOMAIN §7): AUTO confirms a change at once, CONFIRM waits for the
 * new teacher (Telegram or the cabinet). The substitution module maps it to its own
 * {@code ApprovalMode} column type.
 */
public enum ChangeApprovalMode {
    AUTO,
    CONFIRM
}
