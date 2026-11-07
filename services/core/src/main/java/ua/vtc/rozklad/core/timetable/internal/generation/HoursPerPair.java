package ua.vtc.rozklad.core.timetable.internal.generation;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.core.settings.SettingRepository;

/**
 * Academic hours in one pair: setting {@code accounting.hoursPerPair}, 2 by DOMAIN.md §2 when the
 * setting is absent. An unusable value is reported in the log and the DOMAIN default is used.
 */
@Component
class HoursPerPair {

    static final String KEY = "accounting.hoursPerPair";
    static final BigDecimal DOMAIN_DEFAULT = BigDecimal.valueOf(2);

    private static final Logger log = LoggerFactory.getLogger(HoursPerPair.class);

    private final SettingRepository settings;

    HoursPerPair(SettingRepository settings) {
        this.settings = settings;
    }

    BigDecimal current() {
        return settings.findById(KEY)
                .map(setting -> parse(setting.getValue()))
                .orElse(DOMAIN_DEFAULT);
    }

    private static BigDecimal parse(JsonNode value) {
        try {
            BigDecimal hours = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString().strip());
            if (hours.signum() > 0) {
                return hours;
            }
        } catch (NumberFormatException invalid) {
            log.debug("Setting {} is not a number", KEY, invalid);
        }
        log.warn("Setting {} = {} is not a positive number: using {} hours per pair", KEY, value, DOMAIN_DEFAULT);
        return DOMAIN_DEFAULT;
    }
}
