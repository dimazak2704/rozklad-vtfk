package ua.vtc.rozklad.scheduler.replay;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;

/**
 * Real generation requests of the college ({@code generation.requested} as core sent them on 2026-11-15) in
 * {@code src/test/resources/replay/*.json.gz}: public timetable data that is also in {@code seed/}.
 * <ul>
 *   <li>{@code college-from-published}: all 56 groups free, warm start from the published version;</li>
 *   <li>{@code ki4-scope-3groups}: 4КІ1–3 free, everything else pinned;</li>
 *   <li>{@code course4-scope}: the 4th course free;</li>
 *   <li>{@code college-from-scratch}: no base, nothing pinned.</li>
 * </ul>
 * The requests predate this branch's core, so {@link #asNewCoreSends} completes them the way it now sends them.
 */
public final class ReplaySnapshots {

    public static final List<String> REAL = List.of("college-from-published", "ki4-scope-3groups", "course4-scope",
            "college-from-scratch");
    /** The college's daily pair limit for a teacher (core setting {@code generation.teacherMaxPairsPerDay}). */
    static final int TEACHER_LIMIT = 4;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<RoomType> SPORT_ROOMS = EnumSet.of(RoomType.GYM, RoomType.SPORTS_GROUND);

    private ReplaySnapshots() {
    }

    /** The request event: the classpath resource {@code replay/<name>.json.gz}, or a JSON file when {@code name} is a path. */
    public static JsonNode event(String name) {
        try {
            if (name.endsWith(".json") && Files.exists(Path.of(name))) {
                return JSON.readTree(Files.readString(Path.of(name), StandardCharsets.UTF_8));
            }
            try (InputStream raw = ReplaySnapshots.class.getResourceAsStream("/replay/" + name + ".json.gz")) {
                if (raw == null) {
                    throw new IllegalArgumentException("No snapshot replay/" + name + ".json.gz");
                }
                try (InputStream json = new GZIPInputStream(raw)) {
                    return JSON.readTree(new String(json.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ProblemSnapshot snapshot(JsonNode event) {
        return JSON.treeToValue(event.get("problem"), ProblemSnapshot.class);
    }

    /**
     * Completes an old snapshot the way the core of this branch sends it: sibling key {@code loadItemId:suffix}
     * on free subgroup units of an item with two or more of them, and the daily limit for every teacher. No room
     * type is derived (core stopped doing that: keeping PE in the hall is the scheduler's soft {@code specialRooms},
     * read from the base rooms). B3 (the base lesson's teacher) cannot be redone here: the snapshots carry the
     * load's teacher.
     */
    public static ProblemSnapshot asNewCoreSends(ProblemSnapshot snapshot) {
        Map<String, Integer> siblings = new HashMap<>();
        for (ProblemSnapshot.Unit unit : snapshot.units()) {
            String key = siblingKey(unit);
            if (key != null) {
                siblings.merge(key, 1, Integer::sum);
            }
        }
        List<ProblemSnapshot.Unit> units = new ArrayList<>();
        for (ProblemSnapshot.Unit unit : snapshot.units()) {
            String key = siblingKey(unit);
            units.add(new ProblemSnapshot.Unit(unit.unitId(), unit.loadItemId(), unit.groupId(), unit.subgroup(),
                    unit.disciplineId(), unit.teacherId(), unit.weight(), unit.requiredRoomType(), unit.preferredRoomId(),
                    unit.homeRoomId(), unit.pinned(), unit.initial(), unit.teacherRoomId(), unit.online(),
                    key != null && siblings.get(key) >= 2 ? key : unit.siblingKey()));
        }
        List<ProblemSnapshot.Teacher> teachers = snapshot.teachers().stream()
                .map(teacher -> new ProblemSnapshot.Teacher(teacher.id(), teacher.name(), teacher.unavailable(),
                        teacher.unwanted(), teacher.maxPairsPerDay() == null ? TEACHER_LIMIT : teacher.maxPairsPerDay()))
                .toList();
        return new ProblemSnapshot(snapshot.timeslots(), snapshot.shiftWindows(), snapshot.groups(), snapshot.rooms(),
                teachers, units);
    }

    /**
     * Disciplines that belong in a gym or on a sports ground, from the data: every placed (pinned or base) lesson
     * of the discipline that has a room sits in a room of such a type. Used only to measure «PE outside the gym»;
     * a snapshot without placements (from scratch) borrows the set of the published one.
     */
    public static Set<UUID> sportDisciplines(ProblemSnapshot snapshot) {
        Map<UUID, RoomType> roomTypes = snapshot.rooms().stream()
                .collect(Collectors.toMap(ProblemSnapshot.Room::id, ProblemSnapshot.Room::type));
        Map<UUID, Set<Boolean>> inSportRoom = new HashMap<>();
        for (ProblemSnapshot.Unit unit : snapshot.units()) {
            ProblemSnapshot.PinnedSlot placed = unit.pinned() != null ? unit.pinned() : unit.initial();
            if (placed != null && placed.roomId() != null && roomTypes.containsKey(placed.roomId())) {
                inSportRoom.computeIfAbsent(unit.disciplineId(), id -> new HashSet<>())
                        .add(SPORT_ROOMS.contains(roomTypes.get(placed.roomId())));
            }
        }
        return inSportRoom.entrySet().stream()
                .filter(entry -> entry.getValue().equals(Set.of(true)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** {@code loadItemId:suffix} of a free subgroup unit whose id ends with the occurrence suffix. */
    private static String siblingKey(ProblemSnapshot.Unit unit) {
        if (unit.pinned() != null || unit.subgroup() == 0 || unit.loadItemId() == null) {
            return null;
        }
        String id = unit.unitId();
        return unit.loadItemId() + ":" + id.substring(id.lastIndexOf(':') + 1);
    }
}
