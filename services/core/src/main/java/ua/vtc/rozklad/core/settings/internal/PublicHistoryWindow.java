package ua.vtc.rozklad.core.settings.internal;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.settings.PublicHistory;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.HistoryNotPublicException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.security.CurrentUserProvider;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * {@link PublicHistory} from the setting {@code public.changesHistoryDays}. Today and later (for schedules: the
 * current week and later) are open to everyone whatever the setting, so those requests do not read the settings:
 * the hot public pages cost no extra query.
 */
@Component
class PublicHistoryWindow implements PublicHistory {

    private final SettingsService settings;
    private final CurrentUserProvider users;
    private final Clock clock;

    PublicHistoryWindow(SettingsService settings, CurrentUserProvider users, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public LocalDate from() {
        return from(LocalDate.now(clock));
    }

    private LocalDate from(LocalDate today) {
        return today.minusDays(settings.changesHistoryDays());
    }

    @Override
    public boolean fullArchive() {
        return users.find().map(PublicHistoryWindow::seesArchive).orElse(false);
    }

    /** Staff accounts and the bot; the importer and a bare SERVICE token are visitors here. */
    private static boolean seesArchive(CurrentUser user) {
        return user.isAdmin() || user.isTeacher() || user.hasRole(Roles.SERVICE_NOTIFICATION);
    }

    @Override
    public void requireDate(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (!date.isBefore(today) || fullArchive()) {
            return;
        }
        LocalDate from = from(today);
        if (date.isBefore(from)) {
            throw new HistoryNotPublicException(from, "Заміни за дні до %s бачить лише персонал коледжу."
                    .formatted(UkrainianText.date(from)));
        }
    }

    @Override
    public void requireScheduleFrom(LocalDate rangeFrom) {
        LocalDate today = LocalDate.now(clock);
        if (!rangeFrom.isBefore(PublicHistory.weekStart(today)) || fullArchive()) {
            return;
        }
        LocalDate from = from(today);
        LocalDate weekFrom = PublicHistory.weekStart(from);
        if (rangeFrom.isBefore(weekFrom)) {
            throw new HistoryNotPublicException(from, "Розклад із замінами за тижні до %s бачить лише персонал коледжу."
                    .formatted(UkrainianText.date(weekFrom)));
        }
    }
}
