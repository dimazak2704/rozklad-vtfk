package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.api.solver.SolverFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.replay.ReplaySnapshots;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/**
 * Speed gate on real generation requests of the college (GenerationRequested JSON as core sent them).
 * Opt-in: {@code -Dtest=RealSnapshotGateTest -Dgenfix.snapshots=<dir>|classpath} (a directory with the JSON
 * files, else the gzipped copies in {@code src/test/resources/replay}; optional {@code -Dgenfix.seconds.<file>=N},
 * {@code -Dgenfix.newCore=false}). The snapshots predate the sibling keys, the daily limit and the derived
 * room types, so by default they are completed the way the new core sends them
 * ({@link ReplaySnapshots#asNewCoreSends}). The full metric table is {@code SnapshotReplayTest}.
 */
@EnabledIfSystemProperty(named = "genfix.snapshots", matches = ".+")
class RealSnapshotGateTest {

    private static final Logger log = LoggerFactory.getLogger(RealSnapshotGateTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void collegeFromThePublishedVersionStartsAndStaysFeasible() throws Exception {
        Run run = run("college-from-published", 120);

        assertThat(run.firstPlacedScore().hardScore()).as("hard at the first progress point").isZero();
        assertThat(run.finalScore().hardScore()).isZero();
    }

    @Test
    void ki4ScopeReachesZeroHardWithinFiveSeconds() throws Exception {
        Run run = run("ki4-scope-3groups", 30);

        assertThat(run.firstFeasibleAt()).isNotNull().isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(run.finalScore().hardScore()).isZero();
    }

    @Test
    void course4ScopeReachesZeroHard() throws Exception {
        Run run = run("course4-scope", 60);

        assertThat(run.finalScore().hardScore()).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named = "genfix.scratch", matches = "true")
    void collegeFromScratch() throws Exception {
        run("college-from-scratch", 180);
    }

    private Run run(String name, long defaultSeconds) throws Exception {
        // каталог з JSON-файлами або «classpath» — стиснуті запити з src/test/resources/replay
        Path file = Path.of(System.getProperty("genfix.snapshots")).resolve(name + ".json");
        JsonNode event = ReplaySnapshots.event(Files.exists(file) ? file.toString() : name);
        ProblemSnapshot snapshot = ReplaySnapshots.snapshot(event);
        // -Dgenfix.weights=subgroupsTogether=10,stability=0 підміняє окремі ваги запиту
        ObjectNode weightsJson = (ObjectNode) event.get("weights");
        for (String pair : System.getProperty("genfix.weights", "").split(",")) {
            if (pair.contains("=")) {
                weightsJson.put(pair.substring(0, pair.indexOf('=')).trim(),
                        Integer.parseInt(pair.substring(pair.indexOf('=') + 1).trim()));
            }
        }
        GenerationWeights weights = JSON.treeToValue(weightsJson, GenerationWeights.class);
        if (!"false".equals(System.getProperty("genfix.newCore"))) {
            snapshot = ReplaySnapshots.asNewCoreSends(snapshot);
        }
        Timetable problem = new TimetableProblemFactory(5).create(snapshot, weights);
        Metrics base = Metrics.of(placedAtBase(problem));
        long seconds = Long.getLong("genfix.seconds." + name, defaultSeconds);

        Solver<Timetable> solver = SolverFactory.<Timetable>create(TestSolvers.config(TestSolvers.CONFIG,
                new TerminationConfig().withSpentLimit(Duration.ofSeconds(seconds)), EnvironmentMode.NO_ASSERT))
                .buildSolver();
        long start = System.nanoTime();
        AtomicLong placedAt = new AtomicLong(-1);
        AtomicLong feasibleAt = new AtomicLong(-1);
        AtomicReference<HardSoftScore> placedScore = new AtomicReference<>();
        AtomicReference<Metrics> placedMetrics = new AtomicReference<>();
        solver.addEventListener(event1 -> {
            Timetable best = event1.getNewBestSolution();
            if (!best.isFullyPlaced()) {
                return;
            }
            HardSoftScore score = best.getScore();
            if (placedAt.compareAndSet(-1, System.nanoTime() - start)) {
                placedScore.set(score);
                placedMetrics.set(Metrics.of(best));
            }
            if (score.hardScore() == 0) {
                feasibleAt.compareAndSet(-1, System.nanoTime() - start);
            }
        });
        Timetable solution = solver.solve(problem);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Run run = new Run(name, placedScore.get(), nanos(placedAt.get()), nanos(feasibleAt.get()),
                solution.getScore(), elapsed);

        StringBuilder report = new StringBuilder();
        report.append("%n=== %s: %d units, %d free, limit %d s, weights %s%n".formatted(name, problem.getUnits().size(),
                problem.getUnits().stream().filter(unit -> !unit.isPinned()).count(), seconds, weights));
        report.append("first progress point %s after %s; first 0 hard after %s; final %s after %s%n".formatted(
                run.firstPlacedScore(), run.firstPlacedAt(), run.firstFeasibleAt(), run.finalScore(), elapsed));
        report.append(Metrics.header()).append('\n');
        report.append(base.row("base")).append('\n');
        report.append(placedMetrics.get().row("first point")).append('\n');
        report.append(Metrics.of(solution).row("final")).append('\n');
        TimetableAnalyzer.analyze(solution).forEach(row -> report.append("  %-45s %5d (scope %5d) %s%n".formatted(
                row.constraint().title(), row.matchCount(), row.scopeMatchCount(), row.score())));
        log.info("{}", report);
        String reportFile = System.getProperty("genfix.report");
        if (reportFile != null) {
            Files.writeString(Path.of(reportFile), report.toString(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        return run;
    }

    private static Duration nanos(long value) {
        return value < 0 ? null : Duration.ofNanos(value);
    }

    private record Run(String name, HardSoftScore firstPlacedScore, Duration firstPlacedAt, Duration firstFeasibleAt,
                       HardSoftScore finalScore, Duration elapsed) {
    }

    /** Units with a base placement standing there (room-less base lessons in no room). */
    private static Timetable placedAtBase(Timetable problem) {
        Map<UUID, Room> rooms = problem.getRooms().stream()
                .collect(Collectors.toMap(Room::getId, room -> room));
        List<LessonUnit> units = new ArrayList<>();
        for (LessonUnit unit : problem.getUnits()) {
            if (!unit.isPinned() && !unit.hasBase()) {
                continue;
            }
            Timeslot slot = unit.isPinned() ? unit.getTimeslot() : unit.getBaseSlot();
            Parity parity = unit.isPinned() ? unit.getParity() : unit.getBaseParity();
            Room room = unit.isPinned() ? unit.getRoom()
                    : unit.getBaseRoomId() == null ? Room.NONE : rooms.getOrDefault(unit.getBaseRoomId(), Room.NONE);
            LessonUnit.Builder builder = LessonUnit.builder(unit.getId(), unit.getGroup(), unit.getDisciplineId())
                    .loadItemId(unit.getLoadItemId())
                    .subgroup(unit.getSubgroup())
                    .teacher(unit.getTeacher())
                    .weight(unit.getWeight())
                    .requiredRoomType(unit.getRequiredRoomType())
                    .desiredRoom(unit.getDesiredRoom())
                    .siblingKey(unit.getSiblingKey())
                    .softSiblingRule(unit.hasSoftSiblingRule());
            if (unit.hasBase()) {
                builder.base(unit.getBaseSlot(), unit.getBaseParity(), unit.getBaseRoomId());
            }
            units.add(unit.isPinned() ? builder.pinnedAt(slot, room, parity).build()
                    : builder.placedAt(slot, room, parity).build());
        }
        return new Timetable(problem.getTimeslots(), problem.getRooms(), problem.getTeachers(), problem.getGroups(),
                units, problem.getConstraintWeightOverrides());
    }

    /** The numbers of the night study's comparison table, per constraint definition of this branch. */
    private record Metrics(long hard, long soft, Map<String, Long> values) {

        private static final Map<String, TimetableConstraint> IMPACTS = new LinkedHashMap<>();
        private static final Set<TimetableConstraint> COUNT_MATCHES = EnumSet.of(TimetableConstraint.BROKEN_DAY,
                TimetableConstraint.UNIT_MOVED, TimetableConstraint.ROOM_CHANGED, TimetableConstraint.LATE_PAIR);

        static {
            IMPACTS.put("grpWin", TimetableConstraint.GROUP_WINDOWS);
            IMPACTS.put("tchWin", TimetableConstraint.TEACHER_WINDOWS);
            IMPACTS.put("shWait", TimetableConstraint.TEACHER_SHIFT_WAIT);
            IMPACTS.put("broken", TimetableConstraint.BROKEN_DAY);
            IMPACTS.put("lateU", TimetableConstraint.LATE_PAIR);
            IMPACTS.put("grp1p", TimetableConstraint.GROUP_ONE_PAIR_DAY);
            IMPACTS.put("lone", TimetableConstraint.SUBGROUP_ALONE);
            IMPACTS.put("tch1p", TimetableConstraint.TEACHER_ONE_PAIR_DAY);
            IMPACTS.put("xDays", TimetableConstraint.TEACHER_EXTRA_DAY);
            IMPACTS.put("over", TimetableConstraint.TEACHER_OVERLOAD);
            IMPACTS.put("twice", TimetableConstraint.DISCIPLINE_TWICE);
            IMPACTS.put("pref", TimetableConstraint.ROOM_PREFERENCE);
            IMPACTS.put("moved", TimetableConstraint.UNIT_MOVED);
            IMPACTS.put("roomCh", TimetableConstraint.ROOM_CHANGED);
            IMPACTS.put("halves", TimetableConstraint.HALF_PAIRING);
        }

        static Metrics of(Timetable timetable) {
            List<TimetableAnalyzer.ConstraintResult> rows = TimetableAnalyzer.analyze(timetable);
            HardSoftScore total = TimetableAnalyzer.totalScore(rows);
            Map<String, Long> values = new LinkedHashMap<>();
            IMPACTS.forEach((label, constraint) -> rows.stream().filter(row -> row.constraint() == constraint)
                    .findFirst().ifPresent(row -> values.put(label,
                            COUNT_MATCHES.contains(constraint) ? row.matchCount() : row.impact())));
            Map<String, Set<Integer>> days = new HashMap<>();
            Map<String, Set<Integer>> pairs = new HashMap<>();
            for (LessonUnit unit : timetable.getUnits()) {
                if (!unit.hasTeacher()) {
                    continue;
                }
                days.computeIfAbsent(unit.getTeacherId().toString(), id -> new HashSet<>()).add(unit.getWeekday());
                for (int week : unit.weekLanes()) {
                    pairs.computeIfAbsent(unit.getTeacherId() + "/" + unit.getWeekday() + "/" + week,
                            id -> new HashSet<>()).add(unit.getPair());
                }
            }
            values.put("tchDays", days.values().stream().mapToLong(Set::size).sum());
            values.put("tch5+", pairs.values().stream().filter(set -> set.size() >= 5).count());
            return new Metrics(total.hardScore(), total.softScore(), values);
        }

        static String header() {
            StringBuilder text = new StringBuilder("%-12s %6s %8s".formatted("stage", "hard", "soft"));
            IMPACTS.keySet().forEach(label -> text.append(" %7s".formatted(label)));
            text.append(" %7s %7s".formatted("tchDays", "tch5+"));
            return text.toString();
        }

        String row(String stage) {
            StringBuilder text = new StringBuilder("%-12s %6d %8d".formatted(stage, hard, soft));
            values.values().forEach(value -> text.append(" %7d".formatted(value)));
            return text.toString();
        }
    }
}
