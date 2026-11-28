package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** A lesson is over on an earlier day, or today once its end time has come. */
class LessonTimeTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Clock AT_10_40 = Clock.fixed(TODAY.atTime(10, 40).atZone(KYIV).toInstant(), KYIV);

    @Test
    void anEarlierDayIsOverWhateverTheTime() {
        assertThat(LessonTime.isOver(AT_10_40, TODAY.minusDays(1), null)).isTrue();
        assertThat(LessonTime.isOver(AT_10_40, TODAY.minusDays(1), LocalTime.of(23, 0))).isTrue();
    }

    @Test
    void todayIsOverFromTheEndTimeOn() {
        assertThat(LessonTime.isOver(AT_10_40, TODAY, LocalTime.of(10, 39))).isTrue();
        assertThat(LessonTime.isOver(AT_10_40, TODAY, LocalTime.of(10, 40))).isTrue();
        assertThat(LessonTime.isOver(AT_10_40, TODAY, LocalTime.of(10, 41))).isFalse();
    }

    @Test
    void todayWithoutAnEndTimeAndLaterDaysAreNotOver() {
        assertThat(LessonTime.isOver(AT_10_40, TODAY, null)).isFalse();
        assertThat(LessonTime.isOver(AT_10_40, TODAY.plusDays(1), LocalTime.of(8, 0))).isFalse();
    }

    @Test
    void usesTheClockZoneForToday() {
        // 23:30 UTC on the 18th is already the 19th in Kyiv
        Clock lateUtc = Clock.fixed(Instant.parse("2026-11-18T23:30:00Z"), KYIV);
        assertThat(LessonTime.isOver(lateUtc, TODAY.minusDays(1), null)).isTrue();
        assertThat(LessonTime.isOver(lateUtc, TODAY, null)).isFalse();
    }
}
