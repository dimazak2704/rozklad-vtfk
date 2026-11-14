package ua.vtc.rozklad.core.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.HistoryNotPublicException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.security.CurrentUserProvider;
import ua.vtc.rozklad.core.shared.time.TimeConfig;

/** The public history window: a week back by default, whole weeks for schedules, no limit for staff. */
class PublicHistoryWindowTest {

    /** Wednesday 18.11.2026: a week back is Wednesday 11.11, its week starts on Monday 09.11. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 11);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 11, 9);

    private final SettingsService settings = mock(SettingsService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final Clock clock = Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0))
            .atZone(TimeConfig.KYIV).toInstant(), TimeConfig.KYIV);
    private final PublicHistoryWindow window = new PublicHistoryWindow(settings, users, clock);

    @Test
    void windowStartsTheConfiguredNumberOfDaysBeforeTodayOfTheCoreClock() {
        days(7);
        anonymous();

        assertThat(window.from()).isEqualTo(FROM);
        assertThat(window.weekFrom()).isEqualTo(WEEK_FROM);
        assertThat(window.scheduleLimit()).contains(WEEK_FROM);
    }

    @Test
    void visitorSeesTheFirstDayOfTheWindowButNotTheDayBefore() {
        days(7);
        anonymous();

        assertThatCode(() -> window.requireDate(FROM)).doesNotThrowAnyException();
        assertThatThrownBy(() -> window.requireDate(FROM.minusDays(1)))
                .isInstanceOfSatisfying(HistoryNotPublicException.class, refused -> {
                    assertThat(refused.getPublicHistoryFrom()).isEqualTo(FROM);
                    assertThat(refused.getMessage()).isEqualTo("Заміни за дні до 11.11.2026 бачить лише персонал коледжу.");
                });
    }

    @Test
    void todayAndTheFutureAreOpenWithoutReadingTheSettings() {
        anonymous();

        window.requireDate(TODAY);
        window.requireDate(TODAY.plusMonths(3));
        window.requireScheduleFrom(LocalDate.of(2026, 11, 16));
        window.requireScheduleFrom(TODAY.plusWeeks(5));

        verifyNoInteractions(settings);
    }

    @Test
    void scheduleRangesAreLimitedByWholeWeeks() {
        days(7);
        anonymous();

        assertThatCode(() -> window.requireScheduleFrom(WEEK_FROM)).as("Monday of the window's week")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> window.requireScheduleFrom(WEEK_FROM.minusDays(1)))
                .isInstanceOfSatisfying(HistoryNotPublicException.class, refused -> {
                    assertThat(refused.getPublicHistoryFrom()).isEqualTo(FROM);
                    assertThat(refused.getMessage())
                            .isEqualTo("Розклад із замінами за тижні до 09.11.2026 бачить лише персонал коледжу.");
                });
        assertThatThrownBy(() -> window.requireScheduleFrom(WEEK_FROM.minusWeeks(1)))
                .isInstanceOf(HistoryNotPublicException.class);
    }

    @Test
    void zeroDaysLeavesTodayAndTheCurrentWeek() {
        days(0);
        anonymous();

        assertThat(window.from()).isEqualTo(TODAY);
        assertThatThrownBy(() -> window.requireDate(TODAY.minusDays(1))).isInstanceOf(HistoryNotPublicException.class);
        assertThatCode(() -> window.requireScheduleFrom(LocalDate.of(2026, 11, 16))).doesNotThrowAnyException();
        assertThatThrownBy(() -> window.requireScheduleFrom(LocalDate.of(2026, 11, 15)))
                .isInstanceOf(HistoryNotPublicException.class);
    }

    @Test
    void staffAndTheBotSeeTheWholeArchive() {
        days(7);
        for (Set<String> roles : java.util.List.of(Set.of("ADMIN"), Set.of("TEACHER"),
                Set.of("SERVICE", "SERVICE_NOTIFICATION"))) {
            signedIn(roles);

            assertThat(window.fullArchive()).as("%s", roles).isTrue();
            assertThat(window.scheduleLimit()).as("%s", roles).isEmpty();
            assertThatCode(() -> window.requireDate(LocalDate.of(2025, 9, 1))).doesNotThrowAnyException();
            assertThatCode(() -> window.requireScheduleFrom(LocalDate.of(2025, 9, 1))).doesNotThrowAnyException();
        }
    }

    /** Only staff and the bot read the archive: the importer's token and a role-less token are visitors here. */
    @Test
    void otherTokensAreVisitors() {
        days(7);
        for (Set<String> roles : java.util.List.of(Set.of("SERVICE", "SERVICE_IMPORTER"), Set.of("SERVICE"), Set.<String>of())) {
            signedIn(roles);

            assertThat(window.fullArchive()).as("%s", roles).isFalse();
            assertThatThrownBy(() -> window.requireDate(FROM.minusDays(1))).as("%s", roles)
                    .isInstanceOf(HistoryNotPublicException.class);
        }
    }

    private void days(int days) {
        given(settings.changesHistoryDays()).willReturn(days);
    }

    private void anonymous() {
        given(users.find()).willReturn(Optional.empty());
    }

    private void signedIn(Set<String> roles) {
        given(users.find()).willReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "user", roles)));
    }
}
