package ua.vtc.rozklad.scheduler.replay;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.ShapedCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/**
 * Replays a generation request with the production solver configuration and prints the numbers of the
 * generator study (genRun): the reference timetable (published version or planted synthetic one), the first
 * fully placed solution and the final one. Used by {@code SnapshotReplayTest} and runnable as a program for
 * batches ({@code java -cp … SnapshotReplay datasets=college-from-published:180 weights=stability=2 label=A
 * out=results.jsonl}).
 *
 * <p>Datasets: the four real requests of {@link ReplaySnapshots}, a JSON file, or {@code synthetic:<shape>} of
 * {@link ShapedCollege} (from scratch; {@code synthetic:<shape>@warm} starts from the planted timetable).
 * Weights are {@link GenerationWeights#DEFAULT} with the given overrides. Termination is the production one
 * ({@code JobTermination}: the limit, or {@code max(15 s, 25 % of the limit)} without a better score) unless
 * {@code termination=spent}.
 */
public final class SnapshotReplay {

    private static final Logger log = LoggerFactory.getLogger(SnapshotReplay.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Production discipline-repeat weight ({@code app.scheduler.discipline-repeat-weight}). */
    private static final int DISCIPLINE_REPEAT_WEIGHT = 5;
    /** Production {@code app.scheduler.min-unimproved-limit} and {@code unimproved-ratio} (application.yml). */
    private static final Duration MIN_UNIMPROVED = Duration.ofSeconds(15);
    private static final double UNIMPROVED_RATIO = 0.25;
    private static final Set<RoomType> SPORT_ROOMS = EnumSet.of(RoomType.GYM, RoomType.SPORTS_GROUND);

    private SnapshotReplay() {
    }

    /** A problem to solve and the timetable to compare with. */
    public record Dataset(String name, Timetable problem, Reference reference, Set<UUID> sportDisciplines,
                          long defaultSeconds) {
    }

    /** A placed timetable to compare with: the published version or a planted synthetic timetable. */
    public record Reference(String label, Timetable timetable) {
    }

    /** One stage of a run: its score and metrics. */
    public record Row(String stage, HardSoftScore score, ReplayMetrics metrics) {
    }

    /** One replay. Durations are {@code null} when the event never happened. */
    public record Run(String label, String dataset, int units, long freeUnits, GenerationWeights weights,
                      long limitSeconds, Duration firstPlacedAt, Duration feasibleAt, Duration lastImprovementAt,
                      Duration elapsed, Row reference, Row first, Row last, Map<String, String> checks,
                      Map<String, Integer> hardViolations, List<String> details) {
    }

    public static Dataset dataset(String spec, GenerationWeights weights, boolean newCore) {
        TimetableProblemFactory factory = new TimetableProblemFactory(DISCIPLINE_REPEAT_WEIGHT);
        if (spec.startsWith("synthetic:")) {
            String shapeName = spec.substring("synthetic:".length());
            boolean warm = shapeName.endsWith("@warm");
            ShapedCollege.Shape shape = ShapedCollege.Shape.named(warm ? shapeName.substring(0, shapeName.length() - 5)
                    : shapeName);
            ShapedCollege.Problem college = ShapedCollege.generate(shape);
            ProblemSnapshot snapshot = warm ? college.withInitial() : college.snapshot();
            Timetable problem = factory.create(snapshot, weights);
            Timetable planted = college.place(factory.create(college.snapshot(), weights));
            return new Dataset(spec, problem, new Reference("planted", planted), Set.of(), shape.defaultSeconds());
        }
        ProblemSnapshot snapshot = ReplaySnapshots.snapshot(ReplaySnapshots.event(spec));
        Set<UUID> sport = ReplaySnapshots.sportDisciplines(snapshot);
        if (newCore) {
            snapshot = ReplaySnapshots.asNewCoreSends(snapshot);
        }
        Timetable problem = factory.create(snapshot, weights);
        Timetable reference = placedAtBase(problem);
        if (reference.getUnits().isEmpty()) {
            // з нуля: порівнюємо з опублікованою версією того самого семестру
            ProblemSnapshot published = ReplaySnapshots.snapshot(ReplaySnapshots.event("college-from-published"));
            sport = ReplaySnapshots.sportDisciplines(published);
            reference = placedAtBase(factory.create(newCore ? ReplaySnapshots.asNewCoreSends(published) : published,
                    weights));
        }
        long seconds = switch (spec) {
            case "ki4-scope-3groups" -> 60;
            case "course4-scope" -> 120;
            case "college-from-scratch" -> 600;
            default -> 180;
        };
        return new Dataset(spec, problem, new Reference("published", reference), sport, seconds);
    }

    /** Weights: {@link GenerationWeights#DEFAULT} with {@code name=value} overrides separated by commas. */
    public static GenerationWeights weights(String overrides) {
        ObjectNode json = JSON.valueToTree(GenerationWeights.DEFAULT);
        for (String pair : overrides == null ? new String[0] : overrides.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).trim();
            if (!json.has(name)) {
                throw new IllegalArgumentException("Unknown weight " + name);
            }
            json.put(name, Integer.parseInt(pair.substring(eq + 1).trim()));
        }
        return JSON.treeToValue(json, GenerationWeights.class);
    }

    /** @param seed the solver's random seed ({@code null}: production's fixed seed 0) */
    public static Run run(Dataset dataset, String label, GenerationWeights weights, long seconds, boolean production,
                          Long seed) {
        Timetable problem = dataset.problem();
        TerminationConfig termination = new TerminationConfig().withSpentLimit(Duration.ofSeconds(seconds));
        Duration unimproved = Duration.ofMillis(Math.max(MIN_UNIMPROVED.toMillis(),
                Math.round(seconds * 1000 * UNIMPROVED_RATIO)));
        if (production && unimproved.toSeconds() < seconds) {
            termination.withUnimprovedSpentLimit(unimproved);
        }
        Solver<Timetable> solver = SolverFactory.<Timetable>create(
                TestSolvers.config(TestSolvers.CONFIG, termination, EnvironmentMode.NO_ASSERT)
                        .withRandomSeed(seed)).buildSolver();
        Row reference = row(dataset.reference().label(), dataset.reference().timetable(), dataset);
        long start = System.nanoTime();
        AtomicLong placedAt = new AtomicLong(-1);
        AtomicLong feasibleAt = new AtomicLong(-1);
        AtomicLong improvedAt = new AtomicLong(-1);
        AtomicReference<Row> first = new AtomicReference<>();
        solver.addEventListener(event -> {
            Timetable best = event.getNewBestSolution();
            if (!best.isFullyPlaced()) {
                return;
            }
            long now = System.nanoTime() - start;
            improvedAt.set(now);
            if (placedAt.compareAndSet(-1, now)) {
                first.set(row("first", best, dataset));
            }
            if (best.getScore().hardScore() == 0) {
                feasibleAt.compareAndSet(-1, now);
            }
        });
        Timetable solution = solver.solve(problem);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Row last = row("final", solution, dataset);
        return new Run(label, dataset.name(), problem.getUnits().size(),
                problem.getUnits().stream().filter(unit -> !unit.isPinned()).count(), weights, seconds,
                nanos(placedAt.get()), nanos(feasibleAt.get()), nanos(improvedAt.get()), elapsed, reference,
                first.get(), last, checks(solution, last.metrics()), hardViolations(solution),
                details(solution, dataset.sportDisciplines()));
    }

    private static Row row(String stage, Timetable timetable, Dataset dataset) {
        HardSoftScore score = timetable.isFullyPlaced()
                ? TimetableAnalyzer.totalScore(TimetableAnalyzer.analyze(timetable)) : null;
        return new Row(stage, score, ReplayMetrics.of(timetable.getUnits(), ReplayMetrics.placed(timetable),
                dataset.sportDisciplines()));
    }

    /**
     * The solver's own counts next to the independent metrics: equal values mean the constraint counts what the
     * study counts (they differ by design only where the definitions differ, e.g. {@code lone}).
     */
    static Map<String, String> checks(Timetable solution, ReplayMetrics metrics) {
        Map<TimetableConstraint, TimetableAnalyzer.ConstraintResult> rows = new LinkedHashMap<>();
        TimetableAnalyzer.analyze(solution).forEach(row -> rows.put(row.constraint(), row));
        Map<String, String> checks = new LinkedHashMap<>();
        check(checks, "grpWin", rows.get(TimetableConstraint.GROUP_WINDOWS).impact(), metrics.get("grpWin"));
        check(checks, "tchWin", rows.get(TimetableConstraint.TEACHER_WINDOWS).impact(), metrics.get("tchWin"));
        check(checks, "shWait", rows.get(TimetableConstraint.TEACHER_SHIFT_WAIT).impact(), metrics.get("shWait"));
        check(checks, "broken", rows.get(TimetableConstraint.BROKEN_DAY).matchCount(), metrics.get("broken"));
        check(checks, "grp1p", rows.get(TimetableConstraint.GROUP_ONE_PAIR_DAY).impact(), metrics.get("grp1p"));
        check(checks, "tch1p", rows.get(TimetableConstraint.TEACHER_ONE_PAIR_DAY).impact(), metrics.get("tch1p"));
        check(checks, "lone", rows.get(TimetableConstraint.SUBGROUP_ALONE).impact(), metrics.get("lone"));
        check(checks, "sibApart", rows.get(TimetableConstraint.SUBGROUPS_APART).impact()
                + rows.get(TimetableConstraint.SIBLINGS_APART).impact(), metrics.get("sibApart"));
        check(checks, "twice", rows.get(TimetableConstraint.DISCIPLINE_TWICE).impact(), metrics.get("twice"));
        return checks;
    }

    /** What the «peOut» and «grpWin» columns count, one line each: sport units outside a sport room, group windows. */
    static List<String> details(Timetable solution, Set<UUID> sportDisciplines) {
        List<String> units = new ArrayList<>();
        Map<String, List<LessonUnit>> byGroupDay = new TreeMap<>();
        Map<UUID, Set<Integer>> subgroups = new HashMap<>();
        for (LessonUnit unit : solution.getUnits()) {
            if (unit.getSubgroup() > 0) {
                subgroups.computeIfAbsent(unit.getGroupId(), id -> new TreeSet<>()).add(unit.getSubgroup());
            }
            if (unit.isPlaced()) {
                byGroupDay.computeIfAbsent(unit.getGroup().getCode() + " d" + unit.getWeekday(), key -> new ArrayList<>())
                        .add(unit);
            }
        }
        byGroupDay.forEach((key, day) -> {
            Set<Integer> cohorts = subgroups.getOrDefault(day.getFirst().getGroupId(), Set.of(0));
            for (int week = LessonUnit.NUMERATOR_WEEK; week <= LessonUnit.DENOMINATOR_WEEK; week++) {
                int lane = week;
                for (int cohort : cohorts) {
                    SortedSet<Integer> pairs = day.stream()
                            .filter(unit -> unit.getSubgroup() == 0 || unit.getSubgroup() == cohort)
                            .filter(unit -> Arrays.stream(unit.weekLanes()).anyMatch(w -> w == lane))
                            .map(LessonUnit::getPair).collect(Collectors.toCollection(TreeSet::new));
                    if (pairs.size() > 1 && pairs.last() - pairs.first() + 1 > pairs.size()) {
                        units.add("group window: %s%s cohort %d pairs %s".formatted(key,
                                lane == LessonUnit.NUMERATOR_WEEK ? "N" : "D", cohort, pairs));
                    }
                }
            }
        });
        for (LessonUnit unit : solution.getUnits()) {
            boolean sport = sportDisciplines.contains(unit.getDisciplineId())
                    || SPORT_ROOMS.contains(unit.getRequiredRoomType());
            if (sport && unit.isPlaced() && !unit.getRoom().isNone() && !SPORT_ROOMS.contains(unit.getRoom().getType())) {
                units.add("sport unit outside: %s %s required %s desired %s room %s at %s (base %s)".formatted(
                        unit.getId(),
                        unit.getGroup().getCode(), unit.getRequiredRoomType(), unit.getDesiredRoom(), unit.getRoom(),
                        unit.getTimeslot(), unit.getBaseSlot()));
            }
        }
        return units;
    }

    /** Hard constraints the final timetable still breaks: id → matches. */
    static Map<String, Integer> hardViolations(Timetable solution) {
        Map<String, Integer> violations = new LinkedHashMap<>();
        TimetableAnalyzer.analyze(solution).stream()
                .filter(row -> row.constraint().isHard() && row.matchCount() > 0)
                .forEach(row -> violations.put(row.constraint().id(), row.matchCount()));
        return violations;
    }

    private static void check(Map<String, String> checks, String column, long solver, long metric) {
        checks.put(column, solver == metric ? "=" + metric : "solver " + solver + " vs metric " + metric);
    }

    /** Pinned units where they are pinned, free units at their base placement; units without a base left out. */
    static Timetable placedAtBase(Timetable problem) {
        List<LessonUnit> units = new ArrayList<>();
        for (ReplayMetrics.Placement placement : ReplayMetrics.atBase(problem)) {
            LessonUnit unit = placement.unit();
            LessonUnit.Builder builder = LessonUnit.builder(unit.getId(), unit.getGroup(), unit.getDisciplineId())
                    .loadItemId(unit.getLoadItemId())
                    .subgroup(unit.getSubgroup())
                    .teacher(unit.getTeacher())
                    .weight(unit.getWeight())
                    .requiredRoomType(unit.getRequiredRoomType())
                    .desiredRoom(unit.getDesiredRoom())
                    .siblingKey(unit.getSiblingKey());
            if (unit.hasBase()) {
                builder.base(unit.getBaseSlot(), unit.getBaseParity(), unit.getBaseRoomId());
            }
            units.add(unit.isPinned() ? builder.pinnedAt(placement.slot(), placement.room(), placement.parity()).build()
                    : builder.placedAt(placement.slot(), placement.room(), placement.parity()).build());
        }
        return new Timetable(problem.getTimeslots(), problem.getRooms(), problem.getTeachers(), problem.getGroups(),
                units, problem.getConstraintWeightOverrides());
    }

    // ---- report

    public static String report(Run run) {
        StringBuilder text = new StringBuilder();
        text.append("%n=== %s [%s]: %d units, %d free, limit %d s%n".formatted(run.dataset(), run.label(), run.units(),
                run.freeUnits(), run.limitSeconds()));
        text.append("weights %s%n".formatted(run.weights()));
        text.append("first placed %s after %s; first 0 hard after %s; last improvement %s; solved in %s%n".formatted(
                run.first() == null ? null : run.first().score(), seconds(run.firstPlacedAt()),
                seconds(run.feasibleAt()), seconds(run.lastImprovementAt()), seconds(run.elapsed())));
        text.append(header()).append('\n');
        for (Row row : new Row[] {run.reference(), run.first(), run.last()}) {
            if (row != null) {
                text.append(line(row)).append('\n');
            }
        }
        text.append("solver vs metric: ").append(run.checks()).append('\n');
        if (!run.hardViolations().isEmpty()) {
            text.append("hard violations: ").append(run.hardViolations()).append('\n');
        }
        run.details().forEach(line -> text.append("  ").append(line).append('\n'));
        return text.toString();
    }

    static String header() {
        StringBuilder text = new StringBuilder("%-10s %5s %7s".formatted("stage", "hard", "soft"));
        ReplayMetrics.COLUMNS.keySet().forEach(column -> text.append(" %7s".formatted(column)));
        return text.toString();
    }

    static String line(Row row) {
        StringBuilder text = new StringBuilder("%-10s %5s %7s".formatted(row.stage(),
                row.score() == null ? "-" : row.score().hardScore(), row.score() == null ? "-" : row.score().softScore()));
        ReplayMetrics.COLUMNS.keySet().forEach(column -> text.append(" %7d".formatted(row.metrics().get(column))));
        return text.toString();
    }

    private static String seconds(Duration duration) {
        return duration == null ? "never" : "%.1f s".formatted(duration.toMillis() / 1000.0);
    }

    private static Duration nanos(long value) {
        return value < 0 ? null : Duration.ofNanos(value);
    }

    /** One JSON line per run for the evaluation tables. */
    static void append(Path file, Run run) {
        ObjectNode json = JSON.createObjectNode();
        json.put("label", run.label());
        json.put("dataset", run.dataset());
        json.put("units", run.units());
        json.put("freeUnits", run.freeUnits());
        json.put("limitSeconds", run.limitSeconds());
        json.set("weights", JSON.valueToTree(run.weights()));
        json.put("firstPlacedAt", millis(run.firstPlacedAt()));
        json.put("feasibleAt", millis(run.feasibleAt()));
        json.put("lastImprovementAt", millis(run.lastImprovementAt()));
        json.put("elapsed", millis(run.elapsed()));
        for (Row row : new Row[] {run.reference(), run.first(), run.last()}) {
            if (row == null) {
                continue;
            }
            ObjectNode node = json.putObject(row.stage());
            node.put("hard", row.score() == null ? null : row.score().hardScore());
            node.put("soft", row.score() == null ? null : row.score().softScore());
            row.metrics().values().forEach(node::put);
        }
        json.set("checks", JSON.valueToTree(run.checks()));
        json.set("hardViolations", JSON.valueToTree(run.hardViolations()));
        json.set("details", JSON.valueToTree(run.details()));
        try {
            Files.writeString(file, JSON.writeValueAsString(json) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    /** Runs the datasets of {@code datasets=name[:seconds],…} one after another (see the class comment). */
    public static List<Run> runAll(Map<String, String> options) {
        GenerationWeights weights = weights(options.get("weights"));
        String label = options.getOrDefault("label", "default");
        boolean production = !"spent".equals(options.get("termination"));
        boolean newCore = !"false".equals(options.get("newCore"));
        Long seed = options.containsKey("seed") ? Long.valueOf(options.get("seed")) : null;
        List<Run> runs = new ArrayList<>();
        for (String spec : options.getOrDefault("datasets", String.join(",", ReplaySnapshots.REAL)).split(",")) {
            int colon = spec.lastIndexOf(':');
            boolean hasSeconds = colon > 0 && spec.substring(colon + 1).chars().allMatch(Character::isDigit);
            String name = hasSeconds ? spec.substring(0, colon) : spec;
            Dataset dataset = dataset(name, weights, newCore);
            long seconds = hasSeconds ? Long.parseLong(spec.substring(colon + 1)) : dataset.defaultSeconds();
            Run run = run(dataset, label, weights, seconds, production, seed);
            log.info("{}", report(run));
            if (options.containsKey("out")) {
                append(Path.of(options.get("out")), run);
            }
            runs.add(run);
        }
        return runs;
    }

    public static void main(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            options.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        runAll(options);
    }
}
