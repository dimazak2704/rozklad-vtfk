package ua.vtc.rozklad.core.settings.internal;

import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_COLLEGE_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_HOURS_PER_PAIR;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_NAME_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MAX_SIGNATORY_POSITION_LENGTH;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_CONFIRM_TIMEOUT_MINUTES;
import static ua.vtc.rozklad.core.settings.SystemSettings.MIN_HOURS_PER_PAIR;

import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/** Value rules of {@link SystemSettings}; the REST DTO repeats them as annotations for early 400s. */
final class SettingsRules {

    private SettingsRules() {
    }

    static void check(SystemSettings settings, FieldErrors errors) {
        if (settings.confirmTimeoutMinutes() < MIN_CONFIRM_TIMEOUT_MINUTES
                || settings.confirmTimeoutMinutes() > MAX_CONFIRM_TIMEOUT_MINUTES) {
            errors.add("confirmTimeoutMinutes", "Час на підтвердження має бути від %d до %d хвилин."
                    .formatted(MIN_CONFIRM_TIMEOUT_MINUTES, MAX_CONFIRM_TIMEOUT_MINUTES));
        }
        if (settings.hoursPerPair() < MIN_HOURS_PER_PAIR || settings.hoursPerPair() > MAX_HOURS_PER_PAIR) {
            errors.add("hoursPerPair", "Кількість академічних годин за пару має бути від %d до %d."
                    .formatted(MIN_HOURS_PER_PAIR, MAX_HOURS_PER_PAIR));
        }
        text(settings.signatoryPosition(), MAX_SIGNATORY_POSITION_LENGTH, "signatoryPosition", "Посаду підписанта",
                errors);
        text(settings.signatoryName(), MAX_SIGNATORY_NAME_LENGTH, "signatoryName", "Ім'я підписанта", errors);
        text(settings.collegeName(), MAX_COLLEGE_NAME_LENGTH, "collegeName", "Назву коледжу", errors);
    }

    private static void text(String value, int maxLength, String field, String subject, FieldErrors errors) {
        if (value.isBlank()) {
            errors.add(field, subject + " потрібно заповнити.");
        } else if (value.length() > maxLength) {
            errors.add(field, "Не більше %d символів.".formatted(maxLength));
        }
    }
}
