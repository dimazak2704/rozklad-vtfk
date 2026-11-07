package ua.vtc.rozklad.core.order.internal.support;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;

/**
 * Signature of orders from the settings ({@value #SIGNATORY_POSITION_KEY},
 * {@value #SIGNATORY_NAME_KEY}); without settings the signature of the college orders is used
 * («Заступник директора з НР», «Микола ПОЛІЩУК», DOMAIN §8). A stored non-text value is a
 * configuration error.
 */
@Component
public class OrderSettings {

    public static final String SIGNATORY_POSITION_KEY = "order.signatoryPosition";
    public static final String SIGNATORY_NAME_KEY = "order.signatoryName";
    public static final String DEFAULT_SIGNATORY_POSITION = "Заступник директора з НР";
    public static final String DEFAULT_SIGNATORY_NAME = "Микола ПОЛІЩУК";

    private final SettingRepository settings;

    public OrderSettings(SettingRepository settings) {
        this.settings = settings;
    }

    public String signatoryPosition() {
        return text(SIGNATORY_POSITION_KEY, DEFAULT_SIGNATORY_POSITION);
    }

    public String signatoryName() {
        return text(SIGNATORY_NAME_KEY, DEFAULT_SIGNATORY_NAME);
    }

    private String text(String key, String fallback) {
        return settings.findById(key).map(Setting::getValue).map(value -> text(key, value)).orElse(fallback);
    }

    private static String text(String key, JsonNode value) {
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IllegalStateException("Setting " + key + " must be a non-empty string, got " + value);
        }
        return value.stringValue().strip();
    }
}
