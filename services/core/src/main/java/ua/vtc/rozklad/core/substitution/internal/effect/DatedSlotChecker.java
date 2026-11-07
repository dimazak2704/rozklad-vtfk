package ua.vtc.rozklad.core.substitution.internal.effect;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreferenceRepository;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/**
 * Hard conflicts of lessons a change creates (DOMAIN §5, dated lessons): the teacher, the cohort
 * or an exclusive room is already busy in the slot, the room is too small or of the wrong type, or
 * the teacher marked the slot UNAVAILABLE. Lessons the change itself replaces are free. With
 * {@code includeOpenChanges} the slots promised by pending or confirmed changes count as busy too
 * (used when a change is created; on publication only real lessons count). Planned lessons are
 * also checked against each other.
 */
@Component
public class DatedSlotChecker {

    private static final Set<ChangeStatus> OPEN = EnumSet.of(ChangeStatus.DRAFT, ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED);
    private static final Set<ChangeKind> WITHOUT_ORIGINAL = EnumSet.of(ChangeKind.ADDITIONAL, ChangeKind.BLOCK);

    private final LessonRepository lessons;
    private final ChangeRepository changes;
    private final CatalogLookup catalog;
    private final LoadItemRepository loadItems;
    private final TeacherPreferenceRepository preferences;

    public DatedSlotChecker(LessonRepository lessons, ChangeRepository changes, CatalogLookup catalog,
                            LoadItemRepository loadItems, TeacherPreferenceRepository preferences) {
        this.lessons = lessons;
        this.changes = changes;
        this.catalog = catalog;
        this.loadItems = loadItems;
        this.preferences = preferences;
    }

    public List<ConflictDto> check(List<PlannedLesson> planned, Set<UUID> freedLessonIds, UUID ownChangeId,
                                   boolean includeOpenChanges) {
        if (planned.isEmpty()) {
            return List.of();
        }
        Context context = new Context(planned, freedLessonIds, ownChangeId, includeOpenChanges);
        List<ConflictDto> conflicts = new ArrayList<>();
        List<PlannedLesson> accepted = new ArrayList<>();
        for (PlannedLesson lesson : planned) {
            List<Lesson> atSlot = context.lessonsAt(lesson.date(), lesson.pair());
            List<Change> openAtSlot = context.openChangesAt(lesson.date(), lesson.pair());
            teacherConflicts(lesson, atSlot, openAtSlot, accepted, context, conflicts);
            groupConflicts(lesson, atSlot, openAtSlot, accepted, context, conflicts);
            roomConflicts(lesson, atSlot, openAtSlot, accepted, context, conflicts);
            capacityConflict(lesson, context, conflicts);
            roomTypeConflict(lesson, context, conflicts);
            unavailableConflict(lesson, context, conflicts);
            accepted.add(lesson);
        }
        return conflicts.stream().distinct().toList();
    }

    private void teacherConflicts(PlannedLesson lesson, List<Lesson> atSlot, List<Change> openAtSlot,
                                  List<PlannedLesson> accepted, Context context, List<ConflictDto> conflicts) {
        if (lesson.teacherId() == null) {
            return;
        }
        String teacher = context.teacherName(lesson.teacherId());
        atSlot.stream()
                .filter(other -> lesson.teacherId().equals(other.getTeacherId()) && !sameStream(lesson, other))
                .forEach(other -> conflicts.add(hard(ConflictKind.TEACHER, lesson,
                        "%s уже має %d пару: група %s (%s)".formatted(teacher, lesson.pair(),
                                context.groupCode(other.getGroupId()), context.disciplineName(other.getDisciplineId())),
                        other.getId())));
        openAtSlot.stream()
                .filter(change -> lesson.teacherId().equals(change.getNewTeacherId()))
                .filter(change -> !context.sameStreamAsOriginal(lesson, change))
                .forEach(change -> conflicts.add(hard(ConflictKind.TEACHER, lesson,
                        "%s уже призначений на %d пару в групі %s (зміна ще не опублікована)".formatted(teacher,
                                lesson.pair(), context.groupCode(change.getGroupId())))));
        accepted.stream()
                .filter(other -> sameSlot(lesson, other) && lesson.teacherId().equals(other.teacherId()))
                .forEach(other -> conflicts.add(hard(ConflictKind.TEACHER, lesson,
                        "%s отримує дві пари одночасно (%d пара: групи %s і %s)".formatted(teacher, lesson.pair(),
                                context.groupCode(other.groupId()), context.groupCode(lesson.groupId())))));
    }

    private void groupConflicts(PlannedLesson lesson, List<Lesson> atSlot, List<Change> openAtSlot,
                                List<PlannedLesson> accepted, Context context, List<ConflictDto> conflicts) {
        String group = context.groupCode(lesson.groupId()) + cohortSuffix(lesson.subgroup());
        atSlot.stream()
                .filter(other -> other.getGroupId().equals(lesson.groupId())
                        && PlannedLesson.cohortsOverlap(other.getSubgroupNumber(), lesson.subgroup()))
                .forEach(other -> conflicts.add(hard(ConflictKind.GROUP, lesson,
                        "Група %s уже має %d пару: %s".formatted(group, lesson.pair(),
                                context.disciplineName(other.getDisciplineId())), other.getId())));
        openAtSlot.stream()
                .filter(change -> WITHOUT_ORIGINAL.contains(change.getKind()))
                .filter(change -> change.getGroupId().equals(lesson.groupId())
                        && PlannedLesson.cohortsOverlap(change.getSubgroupNumber(), lesson.subgroup()))
                .forEach(change -> conflicts.add(hard(ConflictKind.GROUP, lesson,
                        "Група %s на %d парі вже має іншу зміну (%s)".formatted(group, lesson.pair(),
                                change.getKind() == ChangeKind.BLOCK ? "блок" : "додаткова пара"))));
        accepted.stream()
                .filter(other -> sameSlot(lesson, other) && other.groupId().equals(lesson.groupId())
                        && PlannedLesson.cohortsOverlap(other.subgroup(), lesson.subgroup()))
                .forEach(other -> conflicts.add(hard(ConflictKind.GROUP, lesson,
                        "Група %s отримує дві пари одночасно (%d пара)".formatted(group, lesson.pair()))));
    }

    private void roomConflicts(PlannedLesson lesson, List<Lesson> atSlot, List<Change> openAtSlot,
                               List<PlannedLesson> accepted, Context context, List<ConflictDto> conflicts) {
        if (lesson.roomId() == null) {
            return;
        }
        Room room = context.room(lesson.roomId());
        List<Lesson> occupants = atSlot.stream()
                .filter(other -> lesson.roomId().equals(other.getRoomId()) && !sameStream(lesson, other))
                .toList();
        // зміна, що переносить пару в цю аудиторію, займає її ще до публікації
        List<Change> promised = openAtSlot.stream()
                .filter(change -> lesson.roomId().equals(change.getNewRoomId())
                        && !Objects.equals(change.getNewRoomId(), change.getOrigRoomId()))
                .toList();
        List<PlannedLesson> planned = accepted.stream()
                .filter(other -> sameSlot(lesson, other) && lesson.roomId().equals(other.roomId()))
                .toList();
        List<String> groups = Stream.of(
                        occupants.stream().map(Lesson::getGroupId),
                        promised.stream().map(Change::getGroupId),
                        planned.stream().map(PlannedLesson::groupId))
                .flatMap(ids -> ids)
                .map(context::groupCode)
                .toList();
        long users = groups.size();
        if (users >= room.getMaxParallel()) {
            String message = room.getMaxParallel() == 1
                    ? "Аудиторія %s зайнята на %d парі: група %s".formatted(room.getName(), lesson.pair(),
                    String.join(", ", groups))
                    : "В аудиторії %s на %d парі вже %d %s (максимум %d)".formatted(room.getName(), lesson.pair(),
                    users, UkrainianText.plural(users, "група", "групи", "груп"), room.getMaxParallel());
            conflicts.add(ConflictDto.hard(ConflictKind.ROOM, message,
                    occupants.stream().map(Lesson::getId).toList(), lesson.date(), lesson.pair()));
        }
    }

    private static void capacityConflict(PlannedLesson lesson, Context context, List<ConflictDto> conflicts) {
        if (lesson.roomId() == null || lesson.subgroup() != 0) {
            return;
        }
        Room room = context.room(lesson.roomId());
        StudentGroup group = context.group(lesson.groupId());
        if (room.getCapacity() != null && group.getSize() != null && room.getCapacity() < group.getSize()) {
            conflicts.add(hard(ConflictKind.CAPACITY, lesson,
                    "Аудиторія %s розрахована на %d місць, а в групі %s — %d студентів".formatted(room.getName(),
                            room.getCapacity(), group.getCode(), group.getSize())));
        }
    }

    private void roomTypeConflict(PlannedLesson lesson, Context context, List<ConflictDto> conflicts) {
        if (lesson.roomId() == null) {
            return;
        }
        Room room = context.room(lesson.roomId());
        loadItems.findAllBySemesterIdAndGroupIdAndDisciplineId(lesson.semesterId(), lesson.groupId(),
                        lesson.disciplineId()).stream()
                .map(LoadItem::getRequiredRoomType)
                .filter(Objects::nonNull)
                .findFirst()
                .filter(required -> required != room.getType())
                .ifPresent(required -> conflicts.add(hard(ConflictKind.ROOM_TYPE, lesson,
                        "Для дисципліни %s потрібна аудиторія типу «%s», а %s — «%s»".formatted(
                                context.disciplineName(lesson.disciplineId()), roomTypeLabel(required),
                                room.getName(), roomTypeLabel(room.getType())))));
    }

    private void unavailableConflict(PlannedLesson lesson, Context context, List<ConflictDto> conflicts) {
        if (lesson.teacherId() == null) {
            return;
        }
        int weekday = lesson.date().getDayOfWeek().getValue();
        boolean unavailable = preferences.findAllByTeacherIdAndSemesterId(lesson.teacherId(), lesson.semesterId())
                .stream()
                .anyMatch(preference -> preference.getWeekday() == weekday
                        && preference.getPairNumber() == lesson.pair()
                        && preference.getKind() == PreferenceKind.UNAVAILABLE);
        if (unavailable) {
            conflicts.add(hard(ConflictKind.UNAVAILABLE, lesson,
                    "%s позначив %d пару (%s) як недоступну".formatted(context.teacherName(lesson.teacherId()),
                            lesson.pair(), UkrainianText.weekday(lesson.date().getDayOfWeek()).toLowerCase(
                                    UkrainianText.LOCALE))));
        }
    }

    public static String roomTypeLabel(RoomType type) {
        return switch (type) {
            case CLASSROOM -> "Навчальна аудиторія";
            case COMPUTER_LAB -> "Комп'ютерний клас";
            case LAB -> "Лабораторія";
            case GYM -> "Спортзал";
            case SPORTS_GROUND -> "Спортмайданчик";
            case OTHER -> "Інше";
        };
    }

    private static String cohortSuffix(int subgroup) {
        return subgroup == 0 ? "" : " (підгрупа " + subgroup + ")";
    }

    private static boolean sameSlot(PlannedLesson lesson, PlannedLesson other) {
        return lesson.date().equals(other.date()) && lesson.pair() == other.pair();
    }

    private static boolean sameStream(PlannedLesson lesson, Lesson other) {
        return lesson.streamId() != null && lesson.streamId().equals(other.getStreamId());
    }

    private static ConflictDto hard(ConflictKind kind, PlannedLesson lesson, String message, UUID... lessonIds) {
        return ConflictDto.hard(kind, message, List.of(lessonIds), lesson.date(), lesson.pair());
    }

    /** Everything the checks read, loaded once per call. */
    private final class Context {

        private final Set<UUID> freedLessonIds;
        private final UUID ownChangeId;
        private final boolean includeOpenChanges;
        private final Map<LocalDate, List<Lesson>> lessonsByDate = new HashMap<>();
        private final Map<LocalDate, List<Change>> openChangesByDate = new HashMap<>();
        private final Map<UUID, Lesson> originalsOfOpenChanges = new HashMap<>();
        private final Map<UUID, Teacher> teachers = new HashMap<>();
        private final Map<UUID, StudentGroup> groups = new HashMap<>();
        private final Map<UUID, Room> rooms = new HashMap<>();
        private final Map<UUID, Discipline> disciplines = new HashMap<>();

        Context(List<PlannedLesson> planned, Set<UUID> freedLessonIds, UUID ownChangeId, boolean includeOpenChanges) {
            this.freedLessonIds = freedLessonIds;
            this.ownChangeId = ownChangeId;
            this.includeOpenChanges = includeOpenChanges;
            planned.stream().map(PlannedLesson::date).distinct().forEach(this::loadDay);
            rememberCatalog(planned.stream().map(PlannedLesson::teacherId), planned.stream().map(PlannedLesson::groupId),
                    planned.stream().map(PlannedLesson::roomId), planned.stream().map(PlannedLesson::disciplineId));
        }

        private void loadDay(LocalDate date) {
            List<Lesson> day = lessons.findAllByDateAndStatus(date, LessonStatus.SCHEDULED).stream()
                    .filter(lesson -> !freedLessonIds.contains(lesson.getId()))
                    .toList();
            lessonsByDate.put(date, day);
            List<Change> open = includeOpenChanges
                    ? changes.findAllByDateAndStatusIn(date, OPEN).stream()
                    .filter(change -> !change.getId().equals(ownChangeId))
                    .toList()
                    : List.of();
            openChangesByDate.put(date, open);
            lessons.findAllById(open.stream().map(Change::getOriginalLessonId).filter(Objects::nonNull).toList())
                    .forEach(lesson -> originalsOfOpenChanges.put(lesson.getId(), lesson));
            rememberCatalog(day.stream().map(Lesson::getTeacherId), day.stream().map(Lesson::getGroupId),
                    day.stream().map(Lesson::getRoomId), day.stream().map(Lesson::getDisciplineId));
            rememberCatalog(Stream.empty(), open.stream().map(Change::getGroupId), Stream.empty(), Stream.empty());
        }

        private void rememberCatalog(Stream<UUID> teacherIds, Stream<UUID> groupIds, Stream<UUID> roomIds,
                                     Stream<UUID> disciplineIds) {
            teachers.putAll(catalog.teachers(missing(teacherIds, teachers.keySet())));
            groups.putAll(catalog.groups(missing(groupIds, groups.keySet())));
            rooms.putAll(catalog.rooms(missing(roomIds, rooms.keySet())));
            disciplines.putAll(catalog.disciplines(missing(disciplineIds, disciplines.keySet())));
        }

        private static List<UUID> missing(Stream<UUID> ids, Collection<UUID> known) {
            return ids.filter(Objects::nonNull).filter(id -> !known.contains(id)).distinct().toList();
        }

        List<Lesson> lessonsAt(LocalDate date, int pair) {
            return lessonsByDate.get(date).stream().filter(lesson -> lesson.getPairNumber() == pair).toList();
        }

        List<Change> openChangesAt(LocalDate date, int pair) {
            return openChangesByDate.get(date).stream().filter(change -> change.coversPair(pair)).toList();
        }

        boolean sameStreamAsOriginal(PlannedLesson lesson, Change change) {
            Lesson original = change.getOriginalLessonId() == null ? null
                    : originalsOfOpenChanges.get(change.getOriginalLessonId());
            return lesson.streamId() != null && original != null && lesson.streamId().equals(original.getStreamId());
        }

        String teacherName(UUID id) {
            Teacher teacher = teachers.get(id);
            return teacher == null ? "Викладач" : teacher.getShortName();
        }

        String groupCode(UUID id) {
            StudentGroup group = groups.get(id);
            return group == null ? "?" : group.getCode();
        }

        String disciplineName(UUID id) {
            Discipline discipline = disciplines.get(id);
            return discipline == null ? "?" : discipline.getShortName();
        }

        StudentGroup group(UUID id) {
            return Objects.requireNonNull(groups.get(id), "group " + id);
        }

        Room room(UUID id) {
            return Objects.requireNonNull(rooms.get(id), "room " + id);
        }
    }
}
