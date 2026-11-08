package ua.vtc.rozklad.core.settings;

import ua.vtc.rozklad.core.shared.error.FieldErrors;

/**
 * Extension point for rules that need another module's data, e.g. "the active bell profile
 * exists" (implemented by calendar). Settings stays a leaf module: other modules depend on it,
 * never the other way round. Every bean of this type checks a proposed value before it is saved.
 */
public interface SettingsConstraint {

    /** Adds an error per broken rule; field names are those of {@code SettingsDto}. */
    void check(SystemSettings proposed, FieldErrors errors);
}
