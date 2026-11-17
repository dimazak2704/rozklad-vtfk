package ua.vtc.rozklad.scheduler.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.time.Clock;
import java.time.Duration;
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
import ua.vtc.rozklad.contracts.GenerationStopReason;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.BaseScore;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;
import ua.vtc.rozklad.scheduler.messaging.EventPublisher;
import ua.vtc.rozklad.scheduler.solver.StartingScore;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.FridayBase;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

class GenerationLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    private final GenerationJobRepository repository = mock(GenerationJobRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private static final Duration SECONDS_20 = Duration.ofSeconds(20);

    private final SchedulerProperties properties = new SchedulerProperties(Duration.ofSeconds(2),
            Duration.ofMinutes(15), Duration.ofSeconds(15), 0.25, 5, Duration.ofSeconds(30), Duration.ofDays(30),
            new SchedulerProperties.Auto(Duration.ofMinutes(30), Duration.ofSeconds(60), 20, Duration.ofMinutes(3)));
    private final GenerationLifecycle lifecycle = new GenerationLifecycle(repository, publisher, jsonMapper,
            Clock.fixed(NOW, ZoneOffset.UTC), transactionManager, properties);
    private final SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.tiny());

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(publisher.publishConfirmed(any())).thenReturn(true);
    }

    @Test
    void solvedJobAnnouncesEveryAssignmentAndTheAnalysis() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -42)), SECONDS_20, false, null);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(completed.stopReason()).as("20 s of a 60 s limit: a plateau").isEqualTo(GenerationStopReason.NO_IMPROVEMENT);
        assertThat(completed.softScore()).isEqualTo(-42);
        assertThat(completed.assignments()).hasSameSizeAs(college.planted());
        assertThat(completed.analysis()).hasSize(TimetableConstraint.values().length);
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(job.getBestSoftScore()).isEqualTo(-42);
        assertThat(job.getCompletion()).isEqualTo(jsonMapper.valueToTree(completed));
        assertThat(job.getCompletionSentAt()).isEqualTo(NOW);
    }

    /** The base had a 4th Friday pair: the completion says the run started at a negative hard score. */
    @Test
    void solvedJobAnnouncesTheScoreOfTheTimetableItStartedFrom() {
        GenerationJob job = runningJob();
        BaseScore base = StartingScore.of(TestSolvers.solutionManager(), new TimetableProblemFactory(4)
                .create(FridayBase.snapshot(0), GenerationWeights.DEFAULT));

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -42)), SECONDS_20, false, base);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.baseScore()).isEqualTo(base);
        assertThat(completed.baseScore().hardScore()).isNegative();
        assertThat(completed.hardScore()).isZero();
        assertThat(job.getCompletion().get("baseScore").get("hardScore").asLong()).isEqualTo(base.hardScore());
    }

    @Test
    void failedJobHasNoBaseScore() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), unsolved(), SECONDS_20, false, new BaseScore(-2, -30));

        assertThat(publishedCompletion().baseScore()).isNull();
    }

    @Test
    void cancelledJobKeepsItsBestTimetable() {
        GenerationJob job = runningJob();
        job.requestCancel();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(-2, -100)), SECONDS_20, false, null);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(completed.stopReason()).isEqualTo(GenerationStopReason.STOPPED);
        assertThat(completed.hardScore()).isEqualTo(-2);
        assertThat(completed.assignments()).isNotEmpty();
    }

    @Test
    void fixedLimitJobThatRanItsLimitStoppedAtTheLimit() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -42)), Duration.ofMillis(59_400), false, null);

        assertThat(publishedCompletion().stopReason()).isEqualTo(GenerationStopReason.TIME_LIMIT);
    }

    @Test
    void autoModeJobStopsWithoutImprovementOrAtTheSafetyCap() {
        GenerationJob plateau = autoJob();
        GenerationJob capped = autoJob();

        assertThat(lifecycle.stopReason(plateau, Duration.ofMinutes(4))).isEqualTo(GenerationStopReason.NO_IMPROVEMENT);
        assertThat(lifecycle.stopReason(capped, Duration.ofMinutes(30))).isEqualTo(GenerationStopReason.SAFETY_CAP);
        assertThat(lifecycle.stopReason(capped, Duration.ofSeconds(1799).plusMillis(500)))
                .as("within the tolerance").isEqualTo(GenerationStopReason.SAFETY_CAP);

        lifecycle.finishWithSolution(plateau.getId(), solved(HardSoftScore.of(0, -7)), Duration.ofMinutes(4), false, null);
        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(completed.stopReason()).isEqualTo(GenerationStopReason.NO_IMPROVEMENT);
    }

    @Test
    void autoModeJobThatCouldNotPlaceEveryUnitAsksForTheAdministrator() {
        GenerationJob job = autoJob();

        lifecycle.finishWithSolution(job.getId(), unsolved(), Duration.ofMinutes(30), false, null);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(completed.stopReason()).isNull();
        assertThat(completed.error()).isEqualTo("За найдовший дозволений час вдалося розставити лише 0 з "
                + college.planted().size() + " занять. Повідомте адміністратора.");
    }

    @Test
    void timetableThatIsNotFullyPlacedFailsWithTheNumberOfPlacedUnits() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), unsolved(), Duration.ofSeconds(60), false, null);

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

        lifecycle.finishWithSolution(job.getId(), unsolved(), SECONDS_20, false, null);

        GenerationCompleted completed = publishedCompletion();
        assertThat(completed.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(completed.stopReason()).isNull();
        assertThat(completed.error()).isNull();
        assertThat(completed.assignments()).isEmpty();
    }

    @Test
    void shutdownInterruptsTheJobInsteadOfReportingAShortenedResult() {
        GenerationJob job = runningJob();

        lifecycle.finishWithSolution(job.getId(), solved(HardSoftScore.of(0, -5)), SECONDS_20, true, null);

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

    private GenerationJob autoJob() {
        GenerationJob job = GenerationJob.requested(UUID.randomUUID(), UUID.randomUUID(), null,
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
