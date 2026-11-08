package ua.vtc.rozklad.core.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed system settings (CONTRACTS §2 {@code SettingsDto}, DOMAIN §7–§8). Values come from the
 * {@code setting} table; a key without a row falls back to {@link #DEFAULTS}.
 *
 * @param approvalMode          whether a new teacher must confirm a substitution
 * @param confirmTimeoutMinutes reminder after this many minutes of a pending confirmation
 * @param signatoryPosition     signature line of orders («Заступник директора з НР»)
 * @param signatoryName         signature name of orders («Микола ПОЛІЩУК»)
 * @param collegeName           shown on public pages and documents
 * @param hoursPerPair          academic hours counted per pair (accounting, load patterns)
 * @param showTeacherFullNames  public pages may show full teacher names (otherwise short names only)
 * @param activeBellProfileId   bell profile used when the semester has none ({@code null} = not set)
 */
public record SystemSettings(
        ChangeApprovalMode approvalMode,
        int confirmTimeoutMinutes,
        String signatoryPosition,
        String signatoryName,
        String collegeName,
        int hoursPerPair,
        boolean showTeacherFullNames,
        UUID activeBellProfileId) {

    public static final int MIN_CONFIRM_TIMEOUT_MINUTES = 5;
    public static final int MAX_CONFIRM_TIMEOUT_MINUTES = 1440;
    public static final int MIN_HOURS_PER_PAIR = 1;
    public static final int MAX_HOURS_PER_PAIR = 4;
    public static final int MAX_SIGNATORY_POSITION_LENGTH = 200;
    public static final int MAX_SIGNATORY_NAME_LENGTH = 100;
    public static final int MAX_COLLEGE_NAME_LENGTH = 200;

    /** Values from the college documents (orders №35, №96, №105) and DOMAIN §7–§9. */
    public static final SystemSettings DEFAULTS = new SystemSettings(
            ChangeApprovalMode.CONFIRM,
            120,
            "Заступник директора з НР",
            "Микола ПОЛІЩУК",
            "Вінницький технічний фаховий коледж",
            2,
            false,
            null);

    public SystemSettings {
        Objects.requireNonNull(approvalMode, "approvalMode");
        Objects.requireNonNull(signatoryPosition, "signatoryPosition");
        Objects.requireNonNull(signatoryName, "signatoryName");
        Objects.requireNonNull(collegeName, "collegeName");
    }

    public boolean requiresConfirmation() {
        return approvalMode == ChangeApprovalMode.CONFIRM;
    }

    public Duration confirmTimeout() {
        return Duration.ofMinutes(confirmTimeoutMinutes);
    }
}
