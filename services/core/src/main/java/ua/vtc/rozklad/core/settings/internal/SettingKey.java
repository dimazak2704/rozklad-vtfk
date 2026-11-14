package ua.vtc.rozklad.core.settings.internal;

/**
 * Keys of the {@code setting} table. The default rows are inserted by migration
 * {@code V1_802__setting_defaults.sql} ({@code V1_805} moves the approval mode to NOTIFY);
 * {@code SystemSettings.DEFAULTS} holds the same values. {@code public.changesHistoryDays} has no
 * migration row: until an administrator saves the settings it is the default.
 */
enum SettingKey {
    APPROVAL_MODE("substitution.approvalMode"),
    CONFIRM_TIMEOUT_MINUTES("substitution.confirmTimeoutMinutes"),
    SIGNATORY_POSITION("order.signatoryPosition"),
    SIGNATORY_NAME("order.signatoryName"),
    COLLEGE_NAME("general.collegeName"),
    HOURS_PER_PAIR("accounting.hoursPerPair"),
    SHOW_TEACHER_FULL_NAMES("public.showTeacherFullNames"),
    CHANGES_HISTORY_DAYS("public.changesHistoryDays"),
    ACTIVE_BELL_PROFILE_ID("calendar.activeBellProfileId");

    private final String key;

    SettingKey(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }
}
