package ua.vtc.rozklad.core.timetable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usual rooms of teachers in a weekly timetable (DOMAIN §5, round 7): the room belongs to the
 * teacher and his discipline, groups come to it. The usual room is the room with the most week
 * occurrences of the teacher's lessons (a lesson of both weeks counts 2, a numerator or denominator
 * lesson 1; online lessons and lessons without a room do not count); a tie goes to the lowest room
 * number («22» before «303», numbered rooms before named ones). Lookups go from the most specific
 * key to the widest: teacher + discipline + group, teacher + discipline, teacher. A teacher without
 * lessons in the timetable has no usual room.
 */
public final class TeacherRooms {

    public static final TeacherRooms NONE = new TeacherRooms(List.of());

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d{1,9})");
    private static final Comparator<Tally> BEST = Comparator.comparingInt(Tally::occurrences).reversed()
            .thenComparing(Tally::room, TeacherRooms::byRoomNumber);

    private final Map<UUID, Tally> byTeacher;
    private final Map<Key, Tally> byDiscipline;
    private final Map<Key, Tally> byGroup;

    private TeacherRooms(Collection<RoomUse> uses) {
        Map<UUID, Map<Room, Integer>> teacher = new HashMap<>();
        Map<Key, Map<Room, Integer>> discipline = new HashMap<>();
        Map<Key, Map<Room, Integer>> group = new HashMap<>();
        for (RoomUse use : uses) {
            Room room = new Room(use.roomId(), use.roomName());
            teacher.computeIfAbsent(use.teacherId(), id -> new HashMap<>()).merge(room, use.occurrences(), Integer::sum);
            discipline.computeIfAbsent(new Key(use.teacherId(), use.disciplineId(), null), key -> new HashMap<>())
                    .merge(room, use.occurrences(), Integer::sum);
            group.computeIfAbsent(new Key(use.teacherId(), use.disciplineId(), use.groupId()), key -> new HashMap<>())
                    .merge(room, use.occurrences(), Integer::sum);
        }
        this.byTeacher = best(teacher);
        this.byDiscipline = best(discipline);
        this.byGroup = best(group);
    }

    /** @param uses room uses of lessons, several rows of one key are added up */
    public static TeacherRooms of(Collection<RoomUse> uses) {
        return uses.isEmpty() ? NONE : new TeacherRooms(uses);
    }

    /** The teacher's usual room over all his lessons. */
    public Optional<UUID> forTeacher(UUID teacherId) {
        return Optional.ofNullable(teacherId).map(byTeacher::get).map(tally -> tally.room().id());
    }

    /** The teacher's usual room for the discipline, else over all his lessons. */
    public Optional<UUID> forDiscipline(UUID teacherId, UUID disciplineId) {
        if (teacherId == null) {
            return Optional.empty();
        }
        Tally tally = byDiscipline.get(new Key(teacherId, disciplineId, null));
        return tally != null ? Optional.of(tally.room().id()) : forTeacher(teacherId);
    }

    /** The teacher's usual room for the discipline in the group, else for the discipline, else his usual room. */
    public Optional<UUID> forGroup(UUID teacherId, UUID disciplineId, UUID groupId) {
        if (teacherId == null) {
            return Optional.empty();
        }
        Tally tally = byGroup.get(new Key(teacherId, disciplineId, groupId));
        return tally != null ? Optional.of(tally.room().id()) : forDiscipline(teacherId, disciplineId);
    }

    public boolean isEmpty() {
        return byTeacher.isEmpty();
    }

    private static <K> Map<K, Tally> best(Map<K, Map<Room, Integer>> counts) {
        Map<K, Tally> best = new HashMap<>();
        counts.forEach((key, rooms) -> rooms.entrySet().stream()
                .map(entry -> new Tally(entry.getKey(), entry.getValue()))
                .min(BEST)
                .ifPresent(tally -> best.put(key, tally)));
        return best;
    }

    /** Room number order: leading digits as a number, numbered rooms first, then the name, then the id. */
    static int byRoomNumber(Room first, Room second) {
        long a = number(first.name());
        long b = number(second.name());
        if (a != b) {
            return Long.compare(a, b);
        }
        int byName = Objects.toString(first.name(), "").compareTo(Objects.toString(second.name(), ""));
        return byName != 0 ? byName : first.id().compareTo(second.id());
    }

    private static long number(String name) {
        if (name == null) {
            return Long.MAX_VALUE;
        }
        Matcher matcher = LEADING_NUMBER.matcher(name.strip());
        return matcher.find() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }

    /**
     * Week occurrences of a teacher's lessons of one discipline in one group in one room.
     *
     * @param roomName used only to break ties by room number
     */
    public record RoomUse(UUID teacherId, UUID disciplineId, UUID groupId, UUID roomId, String roomName,
                          int occurrences) {

        public RoomUse {
            Objects.requireNonNull(teacherId, "teacherId");
            Objects.requireNonNull(disciplineId, "disciplineId");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(roomId, "roomId");
        }
    }

    record Room(UUID id, String name) {
    }

    private record Key(UUID teacherId, UUID disciplineId, UUID groupId) {
    }

    private record Tally(Room room, int occurrences) {
    }
}
