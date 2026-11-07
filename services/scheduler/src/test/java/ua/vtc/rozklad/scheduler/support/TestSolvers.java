package ua.vtc.rozklad.scheduler.support;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/** Solvers built from the production {@code solverConfig.xml} for tests and benchmarks. */
public final class TestSolvers {

    public static final String CONFIG = "solverConfig.xml";

    private TestSolvers() {
    }

    public static SolverConfig config(String resource, TerminationConfig termination, EnvironmentMode mode) {
        return SolverConfig.createFromXmlResource(resource)
                .withTerminationConfig(termination)
                .withEnvironmentMode(mode);
    }

    public static SolverFactory<Timetable> factory(TerminationConfig termination) {
        return SolverFactory.create(config(CONFIG, termination, EnvironmentMode.NO_ASSERT));
    }

    public static SolutionManager<Timetable, HardSoftScore> solutionManager() {
        return SolutionManager.create(factory(new TerminationConfig().withSpentLimit(Duration.ofSeconds(1))));
    }

    /** Result of a timed solve: the best solution and when it first had no hard violations. */
    public record TimedResult(Timetable solution, Duration elapsed, Duration firstFeasibleAt) {
    }

    public static TimedResult solveTimed(SolverFactory<Timetable> factory, Timetable problem) {
        Solver<Timetable> solver = factory.buildSolver();
        long start = System.nanoTime();
        AtomicLong feasibleAt = new AtomicLong(-1);
        solver.addEventListener(event -> {
            HardSoftScore score = (HardSoftScore) event.getNewBestScore();
            if (score.hardScore() == 0 && event.getNewBestSolution().isFullyPlaced()) {
                feasibleAt.compareAndSet(-1, System.nanoTime() - start);
            }
        });
        Timetable solution = solver.solve(problem);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Duration feasible = feasibleAt.get() < 0 ? null : Duration.ofNanos(feasibleAt.get());
        return new TimedResult(solution, elapsed, feasible);
    }
}
