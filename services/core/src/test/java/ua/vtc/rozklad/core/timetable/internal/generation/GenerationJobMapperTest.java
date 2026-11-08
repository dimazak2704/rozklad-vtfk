package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

class GenerationJobMapperTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GenerationJobMapper mapper = new GenerationJobMapper(jsonMapper);

    @Test
    void progressPointsStayInTimeOrder() {
        JsonNode points = mapper.withProgressPoint(null, 4.0, -2, -40);
        points = mapper.withProgressPoint(points, 2.0, -5, -90);
        points = mapper.withProgressPoint(points, 6.1, 0, -35);

        assertThat(points).hasSize(3);
        assertThat(points.get(0).get("t").asDouble()).isEqualTo(2.0);
        assertThat(points.get(2).get("soft").asLong()).isEqualTo(-35);
    }

    @Test
    void progressIsCappedSoAMisbehavingSchedulerCannotGrowTheRow() {
        JsonNode points = null;
        for (int second = 0; second < GenerationJobMapper.MAX_PROGRESS_POINTS + 5; second++) {
            points = mapper.withProgressPoint(points, second, 0, -second);
        }

        assertThat(points).hasSize(GenerationJobMapper.MAX_PROGRESS_POINTS);
    }

    @Test
    void jobIsMappedWithItsJsonColumns() {
        GenerationJob job = new GenerationJob(UUID.randomUUID(),
                jsonMapper.readTree("{\"timeLimitSeconds\":120}"), "admin");
        job.recordProgress(Instant.parse("2026-11-07T10:00:00Z"),
                mapper.withProgressPoint(null, 2.0, -1, -10), GenerationJobMapper.score(-1, -10));
        MetricsDto metrics = new MetricsDto(7, 3, 0, 2, 4,
                List.of(new MetricsDto.GroupMetrics(new GroupRef(UUID.randomUUID(), "4КІ1", 4), 1, 0, 2, 4)));
        job.setMetricsAfter(mapper.json(metrics));
        job.setAnalysis(mapper.json(List.of(new ConstraintScore("Вікна груп", 7, "0hard/-70soft"))));

        GenerationJobDto dto = mapper.toDto(job);

        assertThat(dto.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(dto.bestScore()).isEqualTo("-1hard/-10soft");
        assertThat(dto.timeLimitSeconds()).isEqualTo(120);
        assertThat(dto.progress()).containsExactly(new GenerationJobDto.ProgressPoint(2.0, -1, -10));
        assertThat(dto.metricsAfter()).isEqualTo(metrics);
        assertThat(dto.metricsBefore()).isNull();
        assertThat(dto.analysis()).containsExactly(new ConstraintScore("Вікна груп", 7, "0hard/-70soft"));
    }
}
