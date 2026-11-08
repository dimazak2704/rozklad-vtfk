package ua.vtc.rozklad.core.settings.web;

import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_COLLEGE_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_HOURS_PER_PAIR;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_POSITION_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_HOURS_PER_PAIR;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.core.settings.ChangeApprovalMode;
import ua.vtc.rozklad.core.settings.SystemSettings;

/** {@code SettingsDto} of CONTRACTS §2; the same shape is read by GET and written by PUT. */
record SettingsDto(
        @NotNull ChangeApprovalMode approvalMode,
        @NotNull @Min(MIN_CONFIRM_TIMEOUT_MINUTES) @Max(MAX_CONFIRM_TIMEOUT_MINUTES) Integer confirmTimeoutMinutes,
        @NotBlank @Size(max = MAX_SIGNATORY_POSITION_LENGTH) String signatoryPosition,
        @NotBlank @Size(max = MAX_SIGNATORY_NAME_LENGTH) String signatoryName,
        @NotBlank @Size(max = MAX_COLLEGE_NAME_LENGTH) String collegeName,
        @NotNull @Min(MIN_HOURS_PER_PAIR) @Max(MAX_HOURS_PER_PAIR) Integer hoursPerPair,
        @NotNull Boolean showTeacherFullNames,
        UUID activeBellProfileId) {

    static SettingsDto from(SystemSettings settings) {
        return new SettingsDto(settings.approvalMode(), settings.confirmTimeoutMinutes(),
                settings.signatoryPosition(), settings.signatoryName(), settings.collegeName(),
                settings.hoursPerPair(), settings.showTeacherFullNames(), settings.activeBellProfileId());
    }

    SystemSettings toSettings() {
        return new SystemSettings(approvalMode, confirmTimeoutMinutes, signatoryPosition, signatoryName, collegeName,
                hoursPerPair, showTeacherFullNames, activeBellProfileId);
    }
}
