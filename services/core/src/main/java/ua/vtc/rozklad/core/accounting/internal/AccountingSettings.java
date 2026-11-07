package ua.vtc.rozklad.core.accounting.internal;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;

/**
 * Settings of the accounting module. {@value #HOURS_PER_PAIR_KEY} defaults to 2 academic hours
 * (DOMAIN §2) when the setting is absent; a stored value that is not a positive integer is a
 * configuration error and fails loudly.
 */
@Component
public class AccountingSettings {

    static final String HOURS_PER_PAIR_KEY = "accounting.hoursPerPair";
    static final int DEFAULT_HOURS_PER_PAIR = 2;

    private final SettingRepository settings;

    AccountingSettings(SettingRepository settings) {
        this.settings = settings;
    }

    public int hoursPerPair() {
        return settings.findById(HOURS_PER_PAIR_KEY)
                .map(Setting::getValue)
                .map(AccountingSettings::positiveInt)
                .orElse(DEFAULT_HOURS_PER_PAIR);
    }

    private static int positiveInt(JsonNode value) {
        Integer hours = null;
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            hours = value.intValue();
        } else if (value.isString() && value.stringValue().strip().matches("\\d{1,2}")) {
            hours = Integer.parseInt(value.stringValue().strip());
        }
        if (hours == null || hours < 1) {
            throw new IllegalStateException("Setting " + HOURS_PER_PAIR_KEY + " must be a positive integer, got " + value);
        }
        return hours;
    }
}
