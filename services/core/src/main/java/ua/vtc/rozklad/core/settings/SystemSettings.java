package ua.vtc.rozklad.core.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed system settings (CONTRACTS §2 {@code SettingsDto}, DOMAIN §7–§8). Values come from the
 * {@code setting} table; a key without a row falls back to {@link #DEFAULTS}.
 *
 * @param approvalMode          what a new teacher of a substitution gets: a notice (NOTIFY), a request (CONFIRM), nothing (AUTO)
 * @param confirmTimeoutMinutes reminder after this many minutes of a pending confirmation
 * @param signatoryPosition     signature line of orders («Заступник директора з НР»)
 * @param signatoryName         signature name of orders («Микола ПОЛІЩУК»)
 * @param collegeName           shown on public pages and documents
 * @param hoursPerPair          academic hours counted per pair (accounting, load patterns)
 * @param showTeacherFullNames  public pages may show full teacher names (otherwise short names only)
 * @param changesHistoryDays    how many days back anonymous visitors see substitutions and orders
 *                              ({@link PublicHistory}); 0 = today and the future only
 * @param activeBellProfileId   bell profile used when the semester has none ({@code null} = not set)
 * @param teacherMaxPairsPerDay a teacher's daily pair limit for the generator's soft rule «Забагато пар на день
 *                              у викладача» and the metric of overloaded teacher days (not a college rule: in
 *                              the real timetable 35 of 781 teacher-days have 5–6 pairs, so 4 is the norm)
 */
public record SystemSettings(
        ChangeApprovalMode approvalMode,
        int confirmTimeoutMinutes,
        String signatoryPosition,
        String signatoryName,
        String collegeName,
        int hoursPerPair,
        boolean showTeacherFullNames,
        int changesHistoryDays,
        UUID activeBellProfileId,
        int teacherMaxPairsPerDay) {

    public static final int MIN_CONFIRM_TIMEOUT_MINUTES = 5;
    public static final int MAX_CONFIRM_TIMEOUT_MINUTES = 1440;
    public static final int MIN_HOURS_PER_PAIR = 1;
    public static final int MAX_HOURS_PER_PAIR = 4;
    public static final int MAX_SIGNATORY_POSITION_LENGTH = 200;
    public static final int MAX_SIGNATORY_NAME_LENGTH = 100;
    public static final int MAX_COLLEGE_NAME_LENGTH = 200;
    public static final int MIN_CHANGES_HISTORY_DAYS = 0;
    public static final int MAX_CHANGES_HISTORY_DAYS = 365;
    public static final int MIN_TEACHER_MAX_PAIRS_PER_DAY = 1;
    public static final int MAX_TEACHER_MAX_PAIRS_PER_DAY = 8;
    public static final int DEFAULT_TEACHER_MAX_PAIRS_PER_DAY = 4;

    /**
     * Values from the college documents (orders №35, №96, №105) and DOMAIN §7–§9. The approval mode is
     * NOTIFY: the dispatcher assigns and the teacher is told afterwards (college workflow, 08.11.2026).
     * Anonymous visitors see a week of substitution history (owner decision 14.11.2026).
     */
    public static final SystemSettings DEFAULTS = new SystemSettings(
            ChangeApprovalMode.NOTIFY,
            120,
            "Заступник директора з НР",
            "Микола ПОЛІЩУК",
            "Вінницький технічний фаховий коледж",
            2,
            false,
            7,
            null,
            DEFAULT_TEACHER_MAX_PAIRS_PER_DAY);

    public SystemSettings {
        Objects.requireNonNull(approvalMode, "approvalMode");
        Objects.requireNonNull(signatoryPosition, "signatoryPosition");
        Objects.requireNonNull(signatoryName, "signatoryName");
        Objects.requireNonNull(collegeName, "collegeName");
    }

    /** Settings with the default teacher daily limit (callers that do not deal with generation). */
    public SystemSettings(ChangeApprovalMode approvalMode, int confirmTimeoutMinutes, String signatoryPosition,
                          String signatoryName, String collegeName, int hoursPerPair, boolean showTeacherFullNames,
                          int changesHistoryDays, UUID activeBellProfileId) {
        this(approvalMode, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName, hoursPerPair,
                showTeacherFullNames, changesHistoryDays, activeBellProfileId, DEFAULT_TEACHER_MAX_PAIRS_PER_DAY);
    }

    public boolean requiresConfirmation() {
        return approvalMode == ChangeApprovalMode.CONFIRM;
    }

    public Duration confirmTimeout() {
        return Duration.ofMinutes(confirmTimeoutMinutes);
    }
}
