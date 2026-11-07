package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Runs jobs on Timefold's {@link SolverManager} (at most {@code timefold.solver-manager
 * .parallel-solver-count} at once, the rest wait as QUEUED) and routes the solver's callbacks,
 * which arrive on solver threads, to the job lifecycle and the progress tracker.
 */
@Component
public class GenerationSolver {

    private static final Logger log = LoggerFactory.getLogger(GenerationSolver.class);

    private final SolverManager<Timetable> solverManager;
    private final GenerationLifecycle lifecycle;
    private final ProgressTracker progress;
    private volatile boolean shuttingDown;

    public GenerationSolver(SolverManager<Timetable> solverManager, GenerationLifecycle lifecycle,
                            ProgressTracker progress) {
        this.solverManager = solverManager;
        this.lifecycle = lifecycle;
        this.progress = progress;
    }

    public void start(UUID jobId, Timetable problem, TerminationConfig termination) {
        try {
            solverManager.solveBuilder()
                    .withProblemId(jobId)
                    .withProblem(problem)
                    .withConfigOverride(new SolverConfigOverride().withTerminationConfig(termination))
                    .withSolverJobStartedEventConsumer(event -> {
                        progress.track(jobId);
                        lifecycle.markRunning(jobId);
                    })
                    .withBestSolutionEventConsumer(event -> {
                        if (event.solution().isFullyPlaced()) {
                            progress.newBest(jobId, event.solution().getScore());
                        }
                    })
                    .withFinalBestSolutionEventConsumer(event -> {
                        progress.untrack(jobId);
                        lifecycle.finishWithSolution(jobId, event.solution(), shuttingDown);
                    })
                    .withExceptionHandler((id, error) -> {
                        progress.untrack(jobId);
                        log.error("Solving job {} failed", jobId, error);
                        lifecycle.fail(jobId, GenerationLifecycle.SOLVER_ERROR);
                    })
                    .run();
        } catch (RuntimeException e) {
            log.error("Job {} could not be started", jobId, e);
            lifecycle.fail(jobId, GenerationLifecycle.SOLVER_ERROR);
        }
    }

    /** Stops a running job (its best timetable is still reported) or drops a queued one. */
    public void terminate(UUID jobId) {
        solverManager.terminateEarly(jobId);
    }

    /** Jobs that the closing solver manager stops are reported as interrupted, not as solved. */
    @PreDestroy
    void markShuttingDown() {
        shuttingDown = true;
    }
}
