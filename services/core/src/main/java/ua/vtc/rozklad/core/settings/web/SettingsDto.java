package ua.vtc.rozklad.core.settings.web;

import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_CHANGES_HISTORY_DAYS;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_COLLEGE_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_FRIDAY_MAX_PAIRS;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_HOURS_PER_PAIR;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_POSITION_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_CHANGES_HISTORY_DAYS;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_FRIDAY_MAX_PAIRS;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_HOURS_PER_PAIR;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_TEACHER_MAX_PAIRS_PER_DAY;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_TEACHER_MAX_PAIRS_PER_DAY;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.core.settings.ChangeApprovalMode;
import ua.vtc.rozklad.core.settings.SystemSettings;

/**
 * {@code SettingsDto} of CONTRACTS §2; the same shape is read by GET and written by PUT. {@code version}
 * (additive) is the revision GET returned; PUT with an older one is a 409 {@code stale-data}.
 * {@code changesHistoryDays} (additive) is always in GET; a PUT without it keeps the stored value, so a
 * client that does not know the field cannot reset it. {@code teacherMaxPairsPerDay} (additive, genfix) works the
 * same way: the generator's daily limit of a teacher, 1..8, default 4. So does {@code fridayMaxPairs} (additive, owner's
 * rule of 15.11.2026): the most pairs a group has on Friday, a hard rule of the generator, 1..8, default 3.
 * Field messages are shown next to the fields of the settings form, so they say what to enter (as the web form does).
 */
record SettingsDto(
        @NotNull(message = "Оберіть режим погодження замін") ChangeApprovalMode approvalMode,
        @NotNull(message = "Вкажіть, скільки хвилин чекати")
        @Min(value = MIN_CONFIRM_TIMEOUT_MINUTES, message = TIMEOUT_RANGE)
        @Max(value = MAX_CONFIRM_TIMEOUT_MINUTES, message = TIMEOUT_RANGE) Integer confirmTimeoutMinutes,
        @NotBlank(message = "Вкажіть посаду підписанта")
        @Size(max = MAX_SIGNATORY_POSITION_LENGTH, message = "Не довше " + MAX_SIGNATORY_POSITION_LENGTH + " символів")
        String signatoryPosition,
        @NotBlank(message = "Вкажіть імʼя підписанта")
        @Size(max = MAX_SIGNATORY_NAME_LENGTH, message = "Не довше " + MAX_SIGNATORY_NAME_LENGTH + " символів")
        String signatoryName,
        @NotBlank(message = "Вкажіть назву коледжу")
        @Size(max = MAX_COLLEGE_NAME_LENGTH, message = "Не довше " + MAX_COLLEGE_NAME_LENGTH + " символів")
        String collegeName,
        @NotNull(message = "Вкажіть кількість годин")
        @Min(value = MIN_HOURS_PER_PAIR, message = HOURS_RANGE)
        @Max(value = MAX_HOURS_PER_PAIR, message = HOURS_RANGE) Integer hoursPerPair,
        @NotNull Boolean showTeacherFullNames,
        @Min(value = MIN_CHANGES_HISTORY_DAYS, message = HISTORY_RANGE)
        @Max(value = MAX_CHANGES_HISTORY_DAYS, message = HISTORY_RANGE) Integer changesHistoryDays,
        UUID activeBellProfileId,
        @Min(value = MIN_TEACHER_MAX_PAIRS_PER_DAY, message = TEACHER_PAIRS_RANGE)
        @Max(value = MAX_TEACHER_MAX_PAIRS_PER_DAY, message = TEACHER_PAIRS_RANGE) Integer teacherMaxPairsPerDay,
        @Min(value = MIN_FRIDAY_MAX_PAIRS, message = FRIDAY_PAIRS_RANGE)
        @Max(value = MAX_FRIDAY_MAX_PAIRS, message = FRIDAY_PAIRS_RANGE) Integer fridayMaxPairs,
        @NotNull(message = "Оновіть сторінку: бракує версії налаштувань") Long version) {

    static final String TIMEOUT_RANGE =
            "Від " + MIN_CONFIRM_TIMEOUT_MINUTES + " до " + MAX_CONFIRM_TIMEOUT_MINUTES + " хвилин";
    static final String HOURS_RANGE = "Від " + MIN_HOURS_PER_PAIR + " до " + MAX_HOURS_PER_PAIR + " годин";
    static final String TEACHER_PAIRS_RANGE =
            "Від " + MIN_TEACHER_MAX_PAIRS_PER_DAY + " до " + MAX_TEACHER_MAX_PAIRS_PER_DAY + " пар";
    static final String FRIDAY_PAIRS_RANGE =
            "Від " + MIN_FRIDAY_MAX_PAIRS + " до " + MAX_FRIDAY_MAX_PAIRS + " пар";
    static final String HISTORY_RANGE = "Від " + MIN_CHANGES_HISTORY_DAYS + " до " + MAX_CHANGES_HISTORY_DAYS + " днів";

    static SettingsDto from(SystemSettings settings, long version) {
        return new SettingsDto(settings.approvalMode(), settings.confirmTimeoutMinutes(),
                settings.signatoryPosition(), settings.signatoryName(), settings.collegeName(),
                settings.hoursPerPair(), settings.showTeacherFullNames(), settings.changesHistoryDays(),
                settings.activeBellProfileId(), settings.teacherMaxPairsPerDay(), settings.fridayMaxPairs(),
                version);
    }

    /**
     * @param stored the settings in force: an absent {@code changesHistoryDays}, {@code teacherMaxPairsPerDay} or
     *               {@code fridayMaxPairs} keeps its value
     */
    SystemSettings toSettings(SystemSettings stored) {
        return new SystemSettings(approvalMode, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName,
                hoursPerPair, showTeacherFullNames,
                changesHistoryDays != null ? changesHistoryDays : stored.changesHistoryDays(), activeBellProfileId,
                teacherMaxPairsPerDay != null ? teacherMaxPairsPerDay : stored.teacherMaxPairsPerDay(),
                fridayMaxPairs != null ? fridayMaxPairs : stored.fridayMaxPairs());
    }
}
