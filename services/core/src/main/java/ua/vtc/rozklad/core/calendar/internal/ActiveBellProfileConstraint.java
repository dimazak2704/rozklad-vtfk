package ua.vtc.rozklad.core.calendar.internal;

import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.settings.SettingsConstraint;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.FieldErrors;

/** The default bell profile in settings must exist and be active. */
@Component
class ActiveBellProfileConstraint implements SettingsConstraint {

    private final BellProfileRepository profiles;

    ActiveBellProfileConstraint(BellProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public void check(SystemSettings proposed, FieldErrors errors) {
        if (proposed.activeBellProfileId() == null) {
            return;
        }
        profiles.findById(proposed.activeBellProfileId()).ifPresentOrElse(
                profile -> {
                    if (!profile.isActive()) {
                        errors.add("activeBellProfileId", "Профіль дзвінків «%s» вимкнено.".formatted(profile.getName()));
                    }
                },
                () -> errors.add("activeBellProfileId", "Профіль дзвінків не знайдено."));
    }
}
