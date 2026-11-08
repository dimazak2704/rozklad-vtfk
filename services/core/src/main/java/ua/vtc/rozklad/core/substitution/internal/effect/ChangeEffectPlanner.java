package ua.vtc.rozklad.core.substitution.internal.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Computes the effect of a change on the dated lessons as they are now (DOMAIN §7): which lessons
 * get CANCELLED and which new lessons appear. Used when a change is created (conflict preview)
 * and when it is applied on order publication. Lessons the change replaces must still be
 * SCHEDULED; only CANCEL tolerates a lesson that is already cancelled (e.g. by a block).
 */
@Component
public class ChangeEffectPlanner {

    private final LessonRepository lessons;
    private final SubstitutionCalendar calendar;
    private final CatalogLookup catalog;

    public ChangeEffectPlanner(LessonRepository lessons, SubstitutionCalendar calendar, CatalogLookup catalog) {
        this.lessons = lessons;
        this.calendar = calendar;
        this.catalog = catalog;
    }

    public ChangeEffect plan(Change change) {
        return switch (change.getKind()) {
            case REPLACE_TEACHER, ROOM_CHANGE -> replace(change, scheduledOriginal(change), true);
            case REPLACE_DISCIPLINE -> replace(change, scheduledOriginal(change), false);
            case DISCIPLINE_FINISHED -> change.getNewDisciplineId() == null
                    ? new ChangeEffect(List.of(scheduledOriginal(change)), List.of())
                    : replace(change, scheduledOriginal(change), false);
            case CANCEL -> cancel(change);
            case SWAP -> swap(change);
            case ADDITIONAL -> additional(change);
            case BLOCK -> block(change);
        };
    }

    /** The original lesson is replaced in its own slot; the room stays unless a new one is given. */
    private ChangeEffect replace(Change change, Lesson original, boolean sameDiscipline) {
        UUID discipline = change.getNewDisciplineId() != null ? change.getNewDisciplineId()
                : original.getDisciplineId();
        UUID teacher = change.getKind() == ChangeKind.ROOM_CHANGE || change.getNewTeacherId() == null
                ? original.getTeacherId() : change.getNewTeacherId();
        UUID room = change.getNewRoomId() != null ? change.getNewRoomId() : original.getRoomId();
        // потік (одне заняття для кількох груп) зберігаємо лише для тієї самої дисципліни
        UUID stream = sameDiscipline ? original.getStreamId() : null;
        PlannedLesson replacement = new PlannedLesson(original.getSemesterId(), original.getDate(),
                original.getPairNumber(), original.getGroupId(), original.getSubgroupNumber(), discipline, teacher,
                room, original.getLessonKind(), original.isOnline(), stream, LessonOrigin.CHANGE);
        return new ChangeEffect(List.of(original), List.of(replacement));
    }

    private ChangeEffect cancel(Change change) {
        if (change.getOriginalLessonId() == null) {
            throw lessonGone();
        }
        Lesson original = lessons.findById(change.getOriginalLessonId())
                .orElseThrow(() -> NotFoundException.of("Заняття", change.getOriginalLessonId()));
        return new ChangeEffect(original.isScheduled() ? List.of(original) : List.of(), List.of());
    }

    /** The later lesson moves into the slot of the original one; both old lessons are cancelled. */
    private ChangeEffect swap(Change change) {
        Lesson original = scheduledOriginal(change);
        Lesson moved = scheduled(change.getSwapLessonId(), change);
        UUID room = change.getNewRoomId() != null ? change.getNewRoomId() : original.getRoomId();
        PlannedLesson replacement = new PlannedLesson(original.getSemesterId(), original.getDate(),
                original.getPairNumber(), original.getGroupId(), original.getSubgroupNumber(),
                change.getNewDisciplineId(), change.getNewTeacherId(), room, moved.getLessonKind(),
                original.isOnline(), null, LessonOrigin.CHANGE);
        return new ChangeEffect(List.of(original, moved), List.of(replacement));
    }

    private ChangeEffect additional(Change change) {
        SemesterInfo semester = calendar.requireStudyDate(change.getDate());
        StudentGroup group = catalog.group(change.getGroupId());
        boolean online = calendar.onlineFor(change.getDate(), group);
        List<PlannedLesson> created = new ArrayList<>();
        for (int pair = change.getPairFrom(); pair <= change.getPairTo(); pair++) {
            created.add(new PlannedLesson(semester.id(), change.getDate(), pair, change.getGroupId(),
                    change.getSubgroupNumber(), change.getNewDisciplineId(), change.getNewTeacherId(),
                    change.getNewRoomId(), LessonKind.MIXED, online, null, LessonOrigin.ADDITIONAL));
        }
        return new ChangeEffect(List.of(), created);
    }

    /** A block (e.g. a training day) replaces every lesson of the cohort in its pairs. */
    private ChangeEffect block(Change change) {
        SemesterInfo semester = calendar.requireStudyDate(change.getDate());
        StudentGroup group = catalog.group(change.getGroupId());
        boolean online = calendar.onlineFor(change.getDate(), group);
        List<Lesson> replaced = lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(
                        change.getGroupId(), change.getDate(), change.getDate()).stream()
                .filter(Lesson::isScheduled)
                .filter(lesson -> change.coversPair(lesson.getPairNumber()))
                .filter(lesson -> PlannedLesson.cohortsOverlap(lesson.getSubgroupNumber(), change.getSubgroupNumber()))
                .toList();
        List<PlannedLesson> created = new ArrayList<>();
        for (int pair = change.getPairFrom(); pair <= change.getPairTo(); pair++) {
            created.add(new PlannedLesson(semester.id(), change.getDate(), pair, change.getGroupId(),
                    change.getSubgroupNumber(), change.getNewDisciplineId(), change.getNewTeacherId(),
                    change.getNewRoomId(), LessonKind.MIXED, online, null, LessonOrigin.BLOCK));
        }
        return new ChangeEffect(replaced, created);
    }

    /**
     * The lesson of an abandoned (declined) decision was replaced when the timetable or the
     * calendar changed: the decision has nothing to apply to any more.
     */
    private static ConflictException lessonGone() {
        return new ConflictException("Заняття цієї зміни вже немає в розкладі: розклад або календар змінилися "
                + "після відмови. Створіть нове рішення для заняття.");
    }

    private Lesson scheduledOriginal(Change change) {
        return scheduled(change.getOriginalLessonId(), change);
    }

    private Lesson scheduled(UUID lessonId, Change change) {
        if (lessonId == null) {
            throw lessonGone();
        }
        Lesson lesson = lessons.findById(lessonId).orElseThrow(() -> NotFoundException.of("Заняття", lessonId));
        if (!lesson.isScheduled()) {
            String group = catalog.group(lesson.getGroupId()).getCode();
            throw new ConflictException("Заняття вже змінене або скасоване", List.of(ConflictDto.hard(
                    ConflictKind.GROUP,
                    "Заняття групи %s на %d парі вже змінене або скасоване".formatted(group, lesson.getPairNumber()),
                    List.of(lesson.getId()), change.getDate(), lesson.getPairNumber())));
        }
        return lesson;
    }
}
