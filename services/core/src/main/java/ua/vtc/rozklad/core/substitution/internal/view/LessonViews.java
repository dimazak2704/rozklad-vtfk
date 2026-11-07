package ua.vtc.rozklad.core.substitution.internal.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellSlot;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/** Maps dated lessons to {@link LessonView} with all references loaded in batches. */
@Component
public class LessonViews {

    private final CatalogLookup catalog;
    private final ChangeRepository changes;
    private final LessonRepository lessons;
    private final SemesterRepository semesters;
    private final SubstitutionCalendar calendar;

    public LessonViews(CatalogLookup catalog, ChangeRepository changes, LessonRepository lessons,
                       SemesterRepository semesters, SubstitutionCalendar calendar) {
        this.catalog = catalog;
        this.changes = changes;
        this.lessons = lessons;
        this.semesters = semesters;
        this.calendar = calendar;
    }

    public LessonView of(Lesson lesson) {
        return of(List.of(lesson)).getFirst();
    }

    public List<LessonView> of(List<Lesson> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        Map<UUID, Change> changesById = changes.findAllById(
                        source.stream().map(Lesson::getChangeId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Change::getId, Function.identity()));
        Map<UUID, Integer> movedFromPair = lessons.findAllById(changesById.values().stream()
                        .map(Change::getSwapLessonId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Lesson::getId, Lesson::getPairNumber));

        Map<UUID, StudentGroup> groups = catalog.groups(source.stream().map(Lesson::getGroupId).toList());
        Map<UUID, Teacher> teachers = catalog.teachers(Stream.concat(
                source.stream().map(Lesson::getTeacherId),
                changesById.values().stream().map(Change::getOrigTeacherId)).toList());
        Map<UUID, Discipline> disciplines = catalog.disciplines(Stream.concat(
                source.stream().map(Lesson::getDisciplineId),
                changesById.values().stream().map(Change::getOrigDisciplineId)).toList());
        Map<UUID, Room> rooms = catalog.rooms(Stream.concat(
                source.stream().map(Lesson::getRoomId),
                changesById.values().stream().map(Change::getOrigRoomId)).toList());
        Map<UUID, Semester> semestersById = semesters.findAllById(
                        source.stream().map(Lesson::getSemesterId).distinct().toList())
                .stream().collect(Collectors.toMap(Semester::getId, Function.identity()));
        Map<String, Map<Integer, BellSlot>> times = new HashMap<>();

        List<LessonView> views = new ArrayList<>(source.size());
        for (Lesson lesson : source) {
            Change change = lesson.getChangeId() == null ? null : changesById.get(lesson.getChangeId());
            Map<Integer, BellSlot> dayTimes = times.computeIfAbsent(lesson.getSemesterId() + "/" + lesson.getDate(),
                    key -> calendar.pairTimes(semestersById.get(lesson.getSemesterId()), lesson.getDate()));
            BellSlot slot = dayTimes.get(lesson.getPairNumber());
            LessonView.Original original = change == null ? null : new LessonView.Original(
                    CatalogLookup.ref(disciplines.get(change.getOrigDisciplineId())),
                    CatalogLookup.ref(teachers.get(change.getOrigTeacherId())),
                    CatalogLookup.ref(rooms.get(change.getOrigRoomId())),
                    change.getSwapLessonId() == null ? null : movedFromPair.get(change.getSwapLessonId()));
            views.add(new LessonView(
                    lesson.getId(),
                    lesson.getPairNumber(),
                    slot == null ? null : slot.getStartsAt(),
                    slot == null ? null : slot.getEndsAt(),
                    CatalogLookup.ref(disciplines.get(lesson.getDisciplineId())),
                    CatalogLookup.ref(teachers.get(lesson.getTeacherId())),
                    CatalogLookup.ref(rooms.get(lesson.getRoomId())),
                    CatalogLookup.ref(groups.get(lesson.getGroupId())),
                    lesson.getSubgroupNumber(),
                    kind(lesson, change),
                    lesson.isOnline(),
                    original,
                    change == null ? null : change.printedReason(),
                    lesson.getChangeId()));
        }
        return views;
    }

    static LessonView.Kind kind(Lesson lesson, Change change) {
        if (!lesson.isScheduled()) {
            return LessonView.Kind.CANCELLED;
        }
        return switch (lesson.getOrigin()) {
            case TEMPLATE -> LessonView.Kind.REGULAR;
            case ADDITIONAL -> LessonView.Kind.ADDITIONAL;
            case BLOCK -> LessonView.Kind.BLOCK;
            case CHANGE -> {
                if (change == null) {
                    yield LessonView.Kind.SUBSTITUTED;
                }
                yield switch (change.getKind()) {
                    case ROOM_CHANGE -> LessonView.Kind.ROOM_CHANGED;
                    case SWAP -> LessonView.Kind.MOVED;
                    default -> LessonView.Kind.SUBSTITUTED;
                };
            }
        };
    }
}
