package ua.vtc.rozklad.scheduler.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.contracts.GenerationStatus;

class GenerationJobTest {

    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    @Test
    void requestedJobIsQueuedUntilTheSolverStartsIt() {
        GenerationJob job = GenerationJob.requested(UUID.randomUUID(), UUID.randomUUID(), 90, 896, null, NOW);

        assertThat(job.getStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(job.isActive()).isTrue();
        assertThat(job.isNew()).isTrue();
        assertThat(job.start(NOW.plusSeconds(1))).isTrue();
        assertThat(job.getStatus()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(job.start(NOW.plusSeconds(2))).isFalse();
    }

    @Test
    void finishedJobKeepsItsResultAndCannotChangeAnyMore() {
        GenerationJob job = GenerationJob.requested(UUID.randomUUID(), UUID.randomUUID(), 90, 896, null, NOW);
        job.start(NOW);
        job.requestCancel();

        job.finish(GenerationStatus.CANCELLED, 0L, -120L, null, JsonNodeFactory.instance.objectNode(), NOW.plusSeconds(9));

        assertThat(job.isActive()).isFalse();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getBestSoftScore()).isEqualTo(-120L);
        assertThat(job.getFinishedAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(job.getCompletionSentAt()).isNull();
        assertThatThrownBy(job::requestCancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> job.finish(GenerationStatus.SOLVED, 0L, 0L, null,
                JsonNodeFactory.instance.objectNode(), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTerminalStatusesWithAnAnnouncementFinishAJob() {
        GenerationJob job = GenerationJob.unknown(UUID.randomUUID(), NOW);

        assertThat(job.getSemesterId()).isNull();
        assertThatThrownBy(() -> job.finish(GenerationStatus.RUNNING, null, null, null,
                JsonNodeFactory.instance.objectNode(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.finish(GenerationStatus.CANCELLED, null, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
