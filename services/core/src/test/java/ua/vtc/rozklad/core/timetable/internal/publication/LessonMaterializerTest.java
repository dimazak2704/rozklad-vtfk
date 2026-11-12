package ua.vtc.rozklad.core.timetable.internal.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_STP;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.facts;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarFixtures;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.timetable.LessonConflictChecker;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TimetableVersion;

/**
 * Republication effective today at 09:00 (Monday 12.10.2026, bells 08:30 and 09:40): pair 1 began, so
 * it keeps the old version's lesson and counts in accounting; pair 2 and later dates get the new version.
 */
class LessonMaterializerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final Clock NINE_AM = Clock.fixed(LocalDateTime.of(MONDAY, LocalTime.of(9, 0))
            .atZone(TimeConfig.KYIV).toInstant(), TimeConfig.KYIV);

    private final LessonMaterializationRepository repository = mock(LessonMaterializationRepository.class);
    private final CalendarQueries calendar = mock(CalendarQueries.class);
    private final LessonConflictChecker conflicts = mock(LessonConflictChecker.class);
    private final Semester semester = mock(Semester.class);
    private final TimetableVersion version = mock(TimetableVersion.class);
    private final UUID versionId = UUID.randomUUID();
    private final LessonMaterializer materializer = new LessonMaterializer(repository, mock(LessonRepository.class),
            calendar, conflicts, mock(EntityManager.class), NINE_AM);
    private final List<TemplateLesson> template = List.of(
            new TemplateLesson(versionId, 1, 1, Parity.BOTH, G_4KI1, 0, D_STP),
            new TemplateLesson(versionId, 1, 2, Parity.BOTH, G_4KI1, 0, D_STP));

    @BeforeEach
    void calendarOfTheSemester() {
        given(semester.getId()).willReturn(CalendarFixtures.SEMESTER_ID);
        given(semester.getEndsOn()).willReturn(MONDAY.plusDays(7));
        given(version.getId()).willReturn(versionId);
        given(calendar.studyCalendar(CalendarFixtures.SEMESTER_ID)).willReturn(CalendarFixtures.calendar(List.of()));
        given(calendar.bellSlots(MONDAY)).willReturn(List.of(
                new PairTime(1, LocalTime.of(8, 30), LocalTime.of(9, 30)),
                new PairTime(2, LocalTime.of(9, 40), LocalTime.of(10, 40))));
        given(repository.kept(CalendarFixtures.SEMESTER_ID, MONDAY)).willReturn(List.of(
                // урок старої версії на першій парі, що вже йде: лишається і не заважає плану
                new KeptLesson(UUID.randomUUID(), MONDAY, 1, G_4KI1, 0, null, null, true, null, true, false, 4, false,
                        null, false)));
        given(conflicts.conflictsOf(anyCollection())).willReturn(List.of());
    }

    @Test
    void republicationEffectiveTodayKeepsThePairsThatAlreadyBegan() {
        LessonMaterializer.Outcome outcome = materializer.materialize(version, semester, template,
                facts().build(), MONDAY, true);

        verify(repository).deleteReplaceable(CalendarFixtures.SEMESTER_ID, MONDAY, 1);
        assertThat(inserted()).extracting(NewLesson::date, NewLesson::pair).containsExactlyInAnyOrder(
                tuple(MONDAY, 2), tuple(MONDAY.plusDays(7), 1), tuple(MONDAY.plusDays(7), 2));
        assertThat(outcome.materialized()).isEqualTo(3);
        assertThat(outcome.conflicts()).isEmpty();
    }

    @Test
    void firstPublicationFillsTheWholeDay() {
        materializer.materialize(version, semester, template, facts().build(), MONDAY, false);

        verify(repository).deleteReplaceable(CalendarFixtures.SEMESTER_ID, MONDAY, 0);
        assertThat(inserted()).extracting(NewLesson::date, NewLesson::pair).contains(tuple(MONDAY, 2));
    }

    @Test
    void aLaterEffectiveDateReplacesWholeDays() {
        LocalDate tuesday = MONDAY.plusDays(1);
        given(repository.kept(CalendarFixtures.SEMESTER_ID, tuesday)).willReturn(List.of());

        materializer.materialize(version, semester, template, facts().build(), tuesday, true);

        verify(repository).deleteReplaceable(CalendarFixtures.SEMESTER_ID, tuesday, 0);
        assertThat(inserted()).extracting(NewLesson::date, NewLesson::pair).containsExactlyInAnyOrder(
                tuple(MONDAY.plusDays(7), 1), tuple(MONDAY.plusDays(7), 2));
    }

    @SuppressWarnings("unchecked")
    private List<NewLesson> inserted() {
        ArgumentCaptor<List<NewLesson>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insert(captor.capture(), any());
        return captor.getValue();
    }
}
