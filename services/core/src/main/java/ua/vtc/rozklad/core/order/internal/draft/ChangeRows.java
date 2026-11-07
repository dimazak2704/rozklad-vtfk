package ua.vtc.rozklad.core.order.internal.draft;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Resolves the texts of confirmed changes for the order: disciplines by short name («СТП»),
 * teachers by display name («Кренцін», «Крисак Л.»), rooms by name; finds the pair a swapped
 * lesson leaves and the pairs that stay «За розкладом» around a block.
 */
@Component
public class ChangeRows {

    private final StudentGroupRepository groups;
    private final TeacherRepository teachers;
    private final DisciplineRepository disciplines;
    private final RoomRepository rooms;
    private final LessonRepository lessons;

    public ChangeRows(StudentGroupRepository groups, TeacherRepository teachers, DisciplineRepository disciplines,
                      RoomRepository rooms, LessonRepository lessons) {
        this.groups = groups;
        this.teachers = teachers;
        this.disciplines = disciplines;
        this.rooms = rooms;
        this.lessons = lessons;
    }

    /** Rows of the changes of one date, in no particular order (the composer sorts them). */
    public List<ChangeRow> of(List<Change> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<UUID, StudentGroup> groupsById = byId(groups.findAllById(ids(changes.stream().map(Change::getGroupId))),
                StudentGroup::getId);
        Map<UUID, Teacher> teachersById = byId(teachers.findAllById(ids(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigTeacherId(), change.getNewTeacherId())))), Teacher::getId);
        Map<UUID, Discipline> disciplinesById = byId(disciplines.findAllById(ids(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigDisciplineId(), change.getNewDisciplineId())))),
                Discipline::getId);
        Map<UUID, Room> roomsById = byId(rooms.findAllById(ids(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigRoomId(), change.getNewRoomId())))), Room::getId);
        Map<UUID, Lesson> swapped = byId(lessons.findAllById(ids(changes.stream().map(Change::getSwapLessonId))),
                Lesson::getId);

        List<ChangeRow> rows = new ArrayList<>(changes.size());
        for (Change change : changes) {
            StudentGroup group = Objects.requireNonNull(groupsById.get(change.getGroupId()), "group");
            UUID roomId = change.getKind() == ChangeKind.CANCEL ? null
                    : change.getNewRoomId() != null ? change.getNewRoomId() : change.getOrigRoomId();
            Lesson moved = change.getSwapLessonId() == null ? null : swapped.get(change.getSwapLessonId());
            rows.add(new ChangeRow(
                    change.getId(),
                    change.getKind(),
                    group.getCode(),
                    group.getCourse(),
                    change.getSubgroupNumber(),
                    change.getPairFrom(),
                    change.getPairTo(),
                    shortName(disciplinesById.get(change.getOrigDisciplineId())),
                    displayName(teachersById.get(change.getOrigTeacherId())),
                    change.printedReason(),
                    shortName(disciplinesById.get(change.getNewDisciplineId())),
                    displayName(teachersById.get(change.getNewTeacherId())),
                    roomId == null ? null : roomsById.get(roomId).getName(),
                    moved == null ? null : moved.getPairNumber(),
                    change.getKind() == ChangeKind.BLOCK ? pairsAsScheduled(change, changes) : List.of()));
        }
        return rows;
    }

    /** Other SCHEDULED pairs of the group that day that no change of the order touches. */
    private List<Integer> pairsAsScheduled(Change block, List<Change> changes) {
        Set<Integer> touched = new TreeSet<>();
        changes.stream()
                .filter(change -> change.getGroupId().equals(block.getGroupId())
                        && change.getDate().equals(block.getDate()))
                .forEach(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                        .forEach(touched::add));
        changes.stream()
                .filter(change -> change.getSwapLessonId() != null && change.getGroupId().equals(block.getGroupId()))
                .map(Change::getSwapLessonId)
                .flatMap(id -> lessons.findById(id).stream())
                .forEach(lesson -> touched.add(lesson.getPairNumber()));
        LocalDate date = block.getDate();
        return lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(block.getGroupId(), date, date)
                .stream()
                .filter(Lesson::isScheduled)
                .map(Lesson::getPairNumber)
                .filter(pair -> !block.coversPair(pair) && !touched.contains(pair))
                .distinct()
                .sorted()
                .toList();
    }

    private static String shortName(Discipline discipline) {
        return discipline == null ? null : discipline.getShortName();
    }

    private static String displayName(Teacher teacher) {
        return teacher == null ? null : teacher.getDisplayName();
    }

    private static List<UUID> ids(Stream<UUID> ids) {
        return ids.filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, T> byId(List<T> entities, Function<T, UUID> id) {
        return entities.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
