package ua.vtc.rozklad.core.substitution.internal.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.support.ReplacedLessons;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Monthly teacher statistics when an amendment decides again about a substitute's pair (finding R4:
 * Непийвода's substitution of 22.12 cancelled by amendment №49 was counted as done and as her miss).
 */
class SubstitutionTeacherActivityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 22);
    private static final UUID SEMESTER = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID PROGRAMMING = UUID.randomUUID();
    private static final UUID STETS = UUID.randomUUID();
    private static final UUID NEPYIVODA = UUID.randomUUID();
    private static final UUID HANZA = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();
    private static final UUID OTHER_ROOM = UUID.randomUUID();

    private final ChangeRepository changeRepository = mock(ChangeRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final ReplacedLessons replacedLessons = mock(ReplacedLessons.class);
    private final SubstitutionTeacherActivity activity = new SubstitutionTeacherActivity(changeRepository,
            lessonRepository, replacedLessons);
    private final List<Change> changes = new ArrayList<>();
    private final List<Lesson> lessons = new ArrayList<>();

    private Lesson template;

    @BeforeEach
    void stetsPair() {
        template = lesson(LessonOrigin.TEMPLATE, STETS, null);
        given(changeRepository.findAllByDateBetweenAndStatusIn(any(), any(), anyCollection())).willReturn(changes);
        given(lessonRepository.findAllById(any())).willAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            return lessons.stream().filter(lesson -> ids.contains(lesson.getId())).toList();
        });
    }

    @Test
    void substitutionCountsForTheSubstituteAndAMissForTheAbsentTeacher() {
        applied(ChangeKind.REPLACE_TEACHER, template, STETS, NEPYIVODA, ROOM);

        assertThat(stats()).containsEntry(NEPYIVODA, new TeacherActivity(NEPYIVODA, 1, 1, 0))
                .containsEntry(STETS, new TeacherActivity(STETS, 0, 0, 1));
    }

    @Test
    void amendmentCancellingTheSubstitutesPairUndoesTheSubstitutionWithoutAMissForHer() {
        Change replacement = applied(ChangeKind.REPLACE_TEACHER, template, STETS, NEPYIVODA, ROOM);
        Lesson substituted = lesson(LessonOrigin.CHANGE, NEPYIVODA, replacement.getId());
        applied(ChangeKind.CANCEL, substituted, NEPYIVODA, null, null);

        Map<UUID, TeacherActivity> stats = stats();

        assertThat(of(stats, NEPYIVODA)).isEqualTo(new TeacherActivity(NEPYIVODA, 0, 0, 0));
        assertThat(stats.get(STETS).missedPairs()).as("Стець still did not take his pair").isOne();
    }

    @Test
    void amendmentGivingThePairToAnotherSubstituteMovesTheSubstitution() {
        Change replacement = applied(ChangeKind.REPLACE_TEACHER, template, STETS, NEPYIVODA, ROOM);
        Lesson substituted = lesson(LessonOrigin.CHANGE, NEPYIVODA, replacement.getId());
        applied(ChangeKind.REPLACE_TEACHER, substituted, NEPYIVODA, HANZA, ROOM);

        Map<UUID, TeacherActivity> stats = stats();

        assertThat(of(stats, NEPYIVODA)).isEqualTo(new TeacherActivity(NEPYIVODA, 0, 0, 0));
        assertThat(stats.get(HANZA)).isEqualTo(new TeacherActivity(HANZA, 1, 1, 0));
        assertThat(stats.get(STETS).missedPairs()).isOne();
    }

    @Test
    void roomChangeOfTheSubstitutesPairKeepsTheSubstitutionAndIsLookedThrough() {
        Change replacement = applied(ChangeKind.REPLACE_TEACHER, template, STETS, NEPYIVODA, ROOM);
        Lesson substituted = lesson(LessonOrigin.CHANGE, NEPYIVODA, replacement.getId());
        Change roomChange = applied(ChangeKind.ROOM_CHANGE, substituted, NEPYIVODA, null, OTHER_ROOM);
        assertThat(stats().get(NEPYIVODA)).isEqualTo(new TeacherActivity(NEPYIVODA, 1, 1, 0));

        Lesson moved = lesson(LessonOrigin.CHANGE, NEPYIVODA, roomChange.getId());
        applied(ChangeKind.CANCEL, moved, NEPYIVODA, null, null);

        assertThat(of(stats(), NEPYIVODA)).isEqualTo(new TeacherActivity(NEPYIVODA, 0, 0, 0));
    }

    /**
     * Order №58: a block 1-4 (ЗУ Ємельяненко) took ФВ of Бурбела (p1) and Укр.мова of Зоріна (p3);
     * both missed a pair, the block's own teacher did not. A confirmed block counts the same.
     */
    @Test
    void blockOfPairsIsAMissForTheTeachersOfTheLessonsItReplaces() {
        UUID burbela = UUID.randomUUID();
        UUID zorina = UUID.randomUUID();
        UUID yemelianenko = UUID.randomUUID();
        Lesson pe = lesson(LessonOrigin.TEMPLATE, burbela, null);
        Lesson ukrainian = lesson(LessonOrigin.TEMPLATE, zorina, null);
        Change block = new Change(ChangeKind.BLOCK, DAY, 1, 4, GROUP, 0, "admin");
        block.setReplacement(PROGRAMMING, yemelianenko, ROOM);
        block.setReason(ReasonCode.OTHER, "ЗУ");
        block.setStatus(ChangeStatus.APPLIED);
        changes.add(block);
        given(replacedLessons.of(anyCollection())).willReturn(Map.of(block.getId(),
                List.of(pe.getId(), ukrainian.getId())));

        Map<UUID, TeacherActivity> stats = stats();

        assertThat(stats.get(burbela)).isEqualTo(new TeacherActivity(burbela, 0, 0, 1));
        assertThat(stats.get(zorina)).isEqualTo(new TeacherActivity(zorina, 0, 0, 1));
        assertThat(stats).doesNotContainKey(yemelianenko);

        block.setStatus(ChangeStatus.CONFIRMED);
        given(lessonRepository.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(GROUP, DAY, DAY))
                .willReturn(List.of(pe, ukrainian));

        assertThat(stats().get(zorina)).isEqualTo(new TeacherActivity(zorina, 0, 0, 1));
    }

    private Map<UUID, TeacherActivity> stats() {
        return activity.activityBetween(DAY.withDayOfMonth(1), DAY.withDayOfMonth(31)).stream()
                .collect(Collectors.toMap(TeacherActivity::teacherId, Function.identity()));
    }

    /** A teacher without substitutions and misses has no row. */
    private static TeacherActivity of(Map<UUID, TeacherActivity> stats, UUID teacher) {
        return stats.getOrDefault(teacher, new TeacherActivity(teacher, 0, 0, 0));
    }

    private Change applied(ChangeKind kind, Lesson original, UUID origTeacher, UUID newTeacher, UUID newRoom) {
        Change change = new Change(kind, DAY, 3, 3, GROUP, 0, "admin");
        change.setOriginal(original.getId(), PROGRAMMING, origTeacher, ROOM);
        change.setReplacement(kind == ChangeKind.CANCEL || kind == ChangeKind.ROOM_CHANGE ? null : PROGRAMMING,
                newTeacher, newRoom);
        change.setReason(ReasonCode.OTHER, "тест");
        change.setStatus(ChangeStatus.APPLIED);
        changes.add(change);
        return change;
    }

    private Lesson lesson(LessonOrigin origin, UUID teacher, UUID changeId) {
        Lesson lesson = new Lesson(SEMESTER, DAY, 3, GROUP, 0, PROGRAMMING, origin);
        lesson.setTeacherId(teacher);
        lesson.setChangeId(changeId);
        lessons.add(lesson);
        return lesson;
    }
}
