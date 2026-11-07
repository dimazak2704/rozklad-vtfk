package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Throttles {@code generation.progress}: the solver reports every new best timetable (often many
 * per second), the tracker keeps only the latest one per job and {@link #publishProgress()} sends
 * it at most once per {@code app.scheduler.progress-interval} — only when it changed.
 */
@Component
public class ProgressTracker {

    /** Best score of a job and the solving time when it was found. */
    record Point(long elapsedMs, HardSoftScore score) {
    }

    private final Map<UUID, RunningJob> running = new ConcurrentHashMap<>();
    private final GenerationLifecycle lifecycle;
    private final LongSupplier nanoTime;

    @Autowired
    public ProgressTracker(GenerationLifecycle lifecycle) {
        this(lifecycle, System::nanoTime);
    }

    ProgressTracker(GenerationLifecycle lifecycle, LongSupplier nanoTime) {
        this.lifecycle = lifecycle;
        this.nanoTime = nanoTime;
    }

    void track(UUID jobId) {
        running.put(jobId, new RunningJob(nanoTime.getAsLong()));
    }

    /** Called by the solver for every new best timetable; only complete timetables are reported. */
    void newBest(UUID jobId, HardSoftScore score) {
        RunningJob job = running.get(jobId);
        if (job != null) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - job.startNanos);
            job.latest.set(new Point(elapsedMs, score));
        }
    }

    void untrack(UUID jobId) {
        running.remove(jobId);
    }

    int trackedJobs() {
        return running.size();
    }

    @Scheduled(fixedDelayString = "${app.scheduler.progress-interval}")
    public void publishProgress() {
        running.forEach((jobId, job) -> {
            Point point = job.takeUnpublished();
            if (point != null) {
                lifecycle.recordProgress(jobId, point.elapsedMs(), point.score());
            }
        });
    }

    private static final class RunningJob {

        private final long startNanos;
        private final AtomicReference<Point> latest = new AtomicReference<>();
        private Point published;

        private RunningJob(long startNanos) {
            this.startNanos = startNanos;
        }

        /** The latest point if it was not sent yet (only the scheduler thread calls this). */
        private Point takeUnpublished() {
            Point point = latest.get();
            if (point == null || point == published) {
                return null;
            }
            published = point;
            return point;
        }
    }
}
