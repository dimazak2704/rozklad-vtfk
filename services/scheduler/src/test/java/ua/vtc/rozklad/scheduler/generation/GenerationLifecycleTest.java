package ua.vtc.rozklad.scheduler.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.messaging.EventPublisher;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;

class GenerationLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    private final GenerationJobRepository repository = mock(GenerationJobRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GenerationLifecycle lifecycle = new GenerationLifecycle(repository, publisher, jsonMapper,
            Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    private final SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.tiny());

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(publisher.publishConfirmed(any())).thenReturn(true);
    }

    @Test
    void solvedJobAnnouncesEveryAssignmentAndTheAnalysis() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -42)), false);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(completed.softScore()).isEqualTo(-42);
        assertThat(completed.assignments()).hasSameSizeAs(college.planted());
        assertThat(completed.analysis()).hasSize(TimetableConstraint.values().length);
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(job.getBestSoftScore()).isEqualTo(-42);
        assertThat(job.getCompletion()).isEqualTo(jsonMapper.valueToTree(completed));
        assertThat(job.getCompletionSentAt()).isEqualTo(NOW);
    }

    @Test
    void cancelledJobKeepsItsBestTimetable() {
        GenerationJob job = runningJob();
        job.requestCancel();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(-2, -100)), false);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(completed.hardScore()).isEqualTo(-2);
        assertThat(completed.assignments()).isNotEmpty();
    }

    @Test
    void timetableThatIsNotFullyPlacedFailsWithTheNumberOfPlacedUnits() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), unsolved(), false);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(completed.assignments()).isEmpty();
        assertThat(completed.error()).isEqualTo("За відведений час вдалося розставити лише 0 з "
                + college.planted().size() + " занять. Збільште ліміт часу генерації.");
        assertThat(job.getError()).isEqualTo(completed.error());
        assertThat(job.getBestHardScore()).isNull();
    }

    @Test
    void cancelDuringConstructionEndsWithoutResult() {
        GenerationJob job = runningJob();
        job.requestCancel();

        lifecycle.finishWithSolution(job.getId(), unsolved(), false);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(completed.error()).isNull();
        assertThat(completed.assignments()).isEmpty();
    }

    @Test
    void shutdownInterruptsTheJobInsteadOfReportingAShortenedResult() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -5)), true);

        assertThat(publishedCompletion().error()).isEqualTo(GenerationLifecycle.INTERRUPTED);
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void finishedJobIsNotFinishedAgain() {
        GenerationJob job = runningJob();
        lifecycle.fail(job.getId(), "Перша причина.");

        lifecycle.cancelWithoutResult(job.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(job.getError()).isEqualTo("Перша причина.");
        verify(publisher).publishConfirmed(any());
    }

    @Test
    void unconfirmedCompletionStaysPendingForRedelivery() {
        when(publisher.publishConfirmed(any())).thenReturn(false);
        GenerationJob job = runningJob();

        lifecycle.cancelWithoutResult(job.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(job.getCompletion()).isNotNull();
        assertThat(job.getCompletionSentAt()).isNull();
    }

    @Test
    void progressIsAnnouncedOnlyForARunningJob() {
        UUID running = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        when(repository.updateProgress(running, 0, -10, 2_000)).thenReturn(1);
        when(repository.updateProgress(finished, 0, -10, 2_000)).thenReturn(0);

        lifecycle.recordProgress(finished, 2_000, HardSoftScore.of(0, -10));
        verify(publisher, never()).publishAfterCommit(any());

        lifecycle.recordProgress(running, 2_000, HardSoftScore.of(0, -10));
        ArgumentCaptor<GenerationProgress> progress = ArgumentCaptor.forClass(GenerationProgress.class);
        verify(publisher).publishAfterCommit(progress.capture());
        assertThat(progress.getValue().jobId()).isEqualTo(running);
        assertThat(progress.getValue().elapsedMs()).isEqualTo(2_000);
        assertThat(progress.getValue().softScore()).isEqualTo(-10);
    }

    private GenerationJob runningJob() {
        GenerationJob job = GenerationJob.requested(UUID.randomUUID(), UUID.randomUUID(), 60,
                college.planted().size(), null, NOW.minusSeconds(30));
        job.start(NOW.minusSeconds(29));
        when(repository.findForUpdate(job.getId())).thenReturn(Optional.of(job));
        return job;
    }

    private GenerationCompleted publishedCompletion() {
        ArgumentCaptor<GenerationCompleted> captor = ArgumentCaptor.forClass(GenerationCompleted.class);
        verify(publisher).publishConfirmed(captor.capture());
        return captor.getValue();
    }

    private Timetable unsolved() {
        return new TimetableProblemFactory(5).create(college.snapshot(), GenerationWeights.DEFAULT);
    }

    private Timetable solved(HardSoftScore score) {
        Timetable timetable = Placements.apply(unsolved(), college.planted());
        timetable.setScore(score);
        return timetable;
    }
}
