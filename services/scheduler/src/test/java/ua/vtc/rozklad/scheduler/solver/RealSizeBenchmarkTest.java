package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.io.File;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.ShapedCollege;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/**
 * Benchmark at the size and shape of the real college ({@link ShapedCollege.Shape#realShaped()}: 56 groups with
 * 14–19 pairs a week, halves, parallel and crossed subgroups, a gym with 5 places), solved from scratch. Opt-in
 * because it runs for up to 90 s: {@code mvn -pl services/scheduler -am test -Dtest=RealSizeBenchmarkTest
 * -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark=true} (optional {@code -Dbenchmark.seconds},
 * {@code -Dbenchmark.config=<classpath resource or file:path>}, {@code -Dbenchmark.seed},
 * {@code -Dbenchmark.shape=<ShapedCollege shape>}). Until the genfix evaluation it used {@link SyntheticCollege}
 * (at most 15 pairs a week in 2–3-pair days), which is easier than the real college.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RealSizeBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(RealSizeBenchmarkTest.class);
    private static final Duration TARGET = Duration.ofSeconds(90);

    @Test
    void realSizeCollegeReachesZeroHardWithinTarget() {
        ShapedCollege.Shape shape = ShapedCollege.Shape.named(System.getProperty("benchmark.shape", "real-shaped"));
        ShapedCollege.Problem problem = ShapedCollege.generate(shape.withSeed(Long.getLong("benchmark.seed",
                shape.seed())));
        TimetableProblemFactory factory = new TimetableProblemFactory(5);
        Timetable timetable = factory.create(problem.snapshot(), GenerationWeights.DEFAULT);

        SolutionManager<Timetable, HardSoftScore> scores = TestSolvers.solutionManager();
        Timetable plant = Placements.apply(factory.create(problem.snapshot(), GenerationWeights.DEFAULT),
                problem.planted());
        log.info("Size: {} groups, {} units ({} halves, {} subgroup units), {} teachers, {} rooms; planted score {}",
                timetable.getGroups().size(), timetable.getUnits().size(),
                timetable.getUnits().stream().filter(unit -> unit.getParityRange().size() == 2).count(),
                timetable.getUnits().stream().filter(unit -> unit.getSubgroup() > 0).count(),
                timetable.getTeachers().size(), timetable.getRooms().size(), scores.update(plant));

        long seconds = Long.getLong("benchmark.seconds", TARGET.toSeconds());
        String config = System.getProperty("benchmark.config", TestSolvers.CONFIG);
        SolverConfig solverConfig = config.startsWith("file:")
                ? SolverConfig.createFromXmlFile(new File(config.substring("file:".length())))
                : SolverConfig.createFromXmlResource(config);
        SolverFactory<Timetable> solverFactory = SolverFactory.create(solverConfig
                .withTerminationConfig(new TerminationConfig().withSpentLimit(Duration.ofSeconds(seconds)))
                .withEnvironmentMode(EnvironmentMode.NO_ASSERT));
        TestSolvers.TimedResult result = TestSolvers.solveTimed(solverFactory, timetable);

        Timetable solution = result.solution();
        log.info("Config {}: first 0 hard after {}, final score {} after {}", config, result.firstFeasibleAt(),
                solution.getScore(), result.elapsed());
        TimetableResults.analysis(solution).forEach(row ->
                log.info("  {} — {} збігів, {}", row.constraint(), row.matchCount(), row.score()));
        log.info("  placed units: {}", solution.getUnits().stream().filter(LessonUnit::isPlaced).count());

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(result.firstFeasibleAt()).isNotNull().isLessThan(TARGET);
    }
}
