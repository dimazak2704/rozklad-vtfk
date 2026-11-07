package ua.vtc.rozklad.core.substitution.internal.support;

import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;
import ua.vtc.rozklad.core.substitution.ApprovalMode;

/**
 * Settings of the substitution workflow. Absent settings use the documented defaults (approval
 * mode CONFIRM, 60 minutes to confirm); a stored value of the wrong type is a configuration
 * error and fails loudly instead of being ignored.
 */
@Component
public class SubstitutionSettings {

    public static final String APPROVAL_MODE_KEY = "substitution.approvalMode";
    public static final String CONFIRM_TIMEOUT_KEY = "substitution.confirmTimeoutMinutes";
    public static final ApprovalMode DEFAULT_APPROVAL_MODE = ApprovalMode.CONFIRM;
    public static final int DEFAULT_CONFIRM_TIMEOUT_MINUTES = 60;

    private final SettingRepository settings;

    public SubstitutionSettings(SettingRepository settings) {
        this.settings = settings;
    }

    public ApprovalMode approvalMode() {
        return settings.findById(APPROVAL_MODE_KEY)
                .map(Setting::getValue)
                .map(SubstitutionSettings::approvalMode)
                .orElse(DEFAULT_APPROVAL_MODE);
    }

    public Duration confirmTimeout() {
        int minutes = settings.findById(CONFIRM_TIMEOUT_KEY)
                .map(Setting::getValue)
                .map(SubstitutionSettings::positiveMinutes)
                .orElse(DEFAULT_CONFIRM_TIMEOUT_MINUTES);
        return Duration.ofMinutes(minutes);
    }

    private static ApprovalMode approvalMode(JsonNode value) {
        if (value.isString()) {
            String name = value.stringValue().strip().toUpperCase(Locale.ROOT);
            for (ApprovalMode mode : ApprovalMode.values()) {
                if (mode.name().equals(name)) {
                    return mode;
                }
            }
        }
        throw new IllegalStateException("Setting " + APPROVAL_MODE_KEY + " must be AUTO or CONFIRM, got " + value);
    }

    private static int positiveMinutes(JsonNode value) {
        Integer minutes = null;
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            minutes = value.intValue();
        } else if (value.isString() && value.stringValue().strip().matches("\\d{1,5}")) {
            minutes = Integer.parseInt(value.stringValue().strip());
        }
        if (minutes == null || minutes < 1) {
            throw new IllegalStateException("Setting " + CONFIRM_TIMEOUT_KEY + " must be a positive integer, got "
                    + value);
        }
        return minutes;
    }
}
