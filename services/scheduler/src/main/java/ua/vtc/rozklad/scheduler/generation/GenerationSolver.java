package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Runs jobs on Timefold's {@link SolverManager} (at most {@code timefold.solver-manager
 * .parallel-solver-count} at once, the rest wait as QUEUED) and routes the solver's callbacks,
 * which arrive on solver threads, to the job lifecycle and the progress tracker. Lifecycle callbacks run
 * with the MDC of the request that started the job (its {@code requestId} reaches {@code generation.completed});
 * periodic progress events, sent by the tracker's own schedule, carry none.
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
        // колбеки йдуть на потоках солвера: з MDC запиту, щоб generation.completed ніс його X-Request-Id
        Map<String, String> requestMdc = MDC.getCopyOfContextMap();
        try {
            solverManager.solveBuilder()
                    .withProblemId(jobId)
                    .withProblem(problem)
                    .withConfigOverride(new SolverConfigOverride().withTerminationConfig(termination))
                    .withSolverJobStartedEventConsumer(event -> withMdc(requestMdc, () -> {
                        progress.track(jobId);
                        lifecycle.markRunning(jobId);
                    }))
                    .withBestSolutionEventConsumer(event -> {
                        if (event.solution().isFullyPlaced()) {
                            progress.newBest(jobId, event.solution().getScore());
                        }
                    })
                    .withFinalBestSolutionEventConsumer(event -> withMdc(requestMdc, () -> {
                        progress.untrack(jobId);
                        lifecycle.finishWithSolution(jobId, event.solution(), shuttingDown);
                    }))
                    .withExceptionHandler((id, error) -> withMdc(requestMdc, () -> {
                        progress.untrack(jobId);
                        log.error("Solving job {} failed", jobId, error);
                        lifecycle.fail(jobId, GenerationLifecycle.SOLVER_ERROR);
                    }))
                    .run();
        } catch (RuntimeException e) {
            log.error("Job {} could not be started", jobId, e);
            lifecycle.fail(jobId, GenerationLifecycle.SOLVER_ERROR);
        }
    }

    /** Runs a solver callback with the MDC of the request that started the job, then restores the thread's own. */
    static void withMdc(Map<String, String> context, Runnable callback) {
        Map<String, String> own = MDC.getCopyOfContextMap();
        setContext(context);
        try {
            callback.run();
        } finally {
            setContext(own);
        }
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
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
