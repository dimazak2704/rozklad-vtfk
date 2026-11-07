package ua.vtc.rozklad.scheduler.generation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressTrackerTest {

    private final GenerationLifecycle lifecycle = mock(GenerationLifecycle.class);
    private final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    private final ProgressTracker tracker = new ProgressTracker(lifecycle, nanos::get);
    private final UUID job = UUID.randomUUID();

    @Test
    void onlyTheLatestBestScoreOfAnIntervalIsPublished() {
        tracker.track(job);
        advance(1_000);
        tracker.newBest(job, HardSoftScore.of(-3, -500));
        advance(200);
        tracker.newBest(job, HardSoftScore.of(-1, -480));

        tracker.publishProgress();

        verify(lifecycle).recordProgress(job, 1_200, HardSoftScore.of(-1, -480));
        verifyNoMoreInteractions(lifecycle);
    }

    @Test
    void unchangedScoreIsNotRepeated() {
        tracker.track(job);
        tracker.newBest(job, HardSoftScore.of(0, -300));
        tracker.publishProgress();

        tracker.publishProgress();
        advance(5_000);
        tracker.newBest(job, HardSoftScore.of(0, -290));
        tracker.publishProgress();

        verify(lifecycle).recordProgress(job, 0, HardSoftScore.of(0, -300));
        verify(lifecycle).recordProgress(job, 5_000, HardSoftScore.of(0, -290));
        verifyNoMoreInteractions(lifecycle);
    }

    @Test
    void finishedOrUnknownJobsAreIgnored() {
        tracker.newBest(job, HardSoftScore.of(0, -1));
        tracker.track(job);
        tracker.untrack(job);
        tracker.newBest(job, HardSoftScore.of(0, -1));

        tracker.publishProgress();

        verify(lifecycle, never()).recordProgress(any(), anyLong(), any());
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
