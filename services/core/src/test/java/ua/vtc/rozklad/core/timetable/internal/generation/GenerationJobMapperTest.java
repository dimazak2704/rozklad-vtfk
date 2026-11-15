package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

class GenerationJobMapperTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final StudentGroupRepository groups = mock(StudentGroupRepository.class);
    private final GenerationJobMapper mapper = new GenerationJobMapper(jsonMapper, groups);

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
        assertThat(dto.hardScore()).isEqualTo(-1L);
        assertThat(dto.softScore()).isEqualTo(-10L);
        assertThat(dto.timeLimitSeconds()).isEqualTo(120);
        assertThat(dto.progress()).containsExactly(new GenerationJobDto.ProgressPoint(2.0, -1, -10));
        assertThat(dto.metricsAfter()).isEqualTo(metrics);
        assertThat(dto.metricsBefore()).isNull();
        assertThat(dto.analysis()).containsExactly(new ConstraintScore("Вікна груп", 7, "0hard/-70soft"));
        assertThat(dto.outcome()).isNull();
        assertThat(dto.groups()).isEmpty();
        assertThat(dto.legacyWeights()).as("no weights stored").isNull();
    }

    /** genfix repair: a job that stored the genfix weights is not legacy, also when a later weight is absent. */
    @Test
    void aJobWithTheGenfixWeightsIsNotLegacy() {
        GenerationJob job = new GenerationJob(UUID.randomUUID(), jsonMapper.readTree("""
                {"weights":{"windows":30,"brokenDays":10,"latePairs":9,"preferences":5,"homeRoom":1,"teacherWindows":10,
                 "teacherShiftWait":3,"stability":1,"subgroupsTogether":10,"studentOnePairDay":10,"teacherDays":5,
                 "teacherOverload":3,"disciplineTwice":10}}
                """), "admin");

        GenerationJobDto dto = mapper.toDto(job);

        assertThat(dto.legacyWeights()).isFalse();
        // the job's own weights (the genfix defaults of that time), only the absent specialRooms takes its default
        assertThat(dto.weights()).isEqualTo(new GenerationWeights(30, 10, 9, 5, 1, 10, 3, 1, 10, 10, 5, 3, 10,
                GenerationWeights.DEFAULT_SPECIAL_ROOMS));
    }

    /**
     * genfix §5: a job stored before the additive fields (five weights, metrics and analysis of the first
     * schema, no outcome) still maps; the DTO shows what the job was asked, with the groups resolved.
     */
    @Test
    void anOldJobMapsWithItsScopeGroupsAndNormalisedWeights() {
        StudentGroup ki2 = new StudentGroup("4КІ2", 4, UUID.randomUUID());
        StudentGroup ki1 = new StudentGroup("4КІ1", 4, UUID.randomUUID());
        StudentGroup tk = new StudentGroup("2ТК", 2, UUID.randomUUID());
        given(groups.findAllById(any())).willReturn(List.of(ki2, ki1, tk));
        GenerationJob job = new GenerationJob(UUID.randomUUID(), jsonMapper.readTree("""
                {"timeLimitSeconds":120,"weights":{"windows":7,"brokenDays":10,"latePairs":30,"preferences":5,
                 "homeRoom":1},"scope":{"groupIds":[],"courses":[4,2]},
                 "groupIds":["%s","%s","%s"]}
                """.formatted(ki2.getId(), ki1.getId(), tk.getId())), "admin");
        job.setMetricsBefore(jsonMapper.readTree("""
                {"groupWindows":0,"teacherWindows":309,"brokenDays":34,"latePairs":321,"maxPairsPerDay":4,
                 "perGroup":[{"group":{"id":"%s","code":"4КІ1","course":4},"windows":0,"brokenDays":1,
                 "latePairs":6,"maxPairsPerDay":4}]}
                """.formatted(ki1.getId())));
        job.setAnalysis(jsonMapper.readTree("""
                [{"constraint":"Вікна груп","matchCount":3,"score":"0hard/-30soft"}]
                """));

        GenerationJobDto dto = mapper.toDto(job);

        assertThat(dto.weights()).isEqualTo(new GenerationWeights(7, 10, 30, 5, 1));
        assertThat(dto.weights().teacherWindows()).isEqualTo(7);
        assertThat(dto.legacyWeights()).as("only the five weights of the first schema were stored").isTrue();
        assertThat(dto.scope()).isEqualTo(new GenerationJobDto.Scope(List.of(), List.of(4, 2)));
        assertThat(dto.groups()).extracting(GroupRef::code).containsExactly("2ТК", "4КІ1", "4КІ2");
        assertThat(dto.metricsBefore().teacherWindows()).isEqualTo(309);
        assertThat(dto.metricsBefore().hardConflicts()).as("unknown in old metrics").isNull();
        assertThat(dto.metricsBefore().perGroup().getFirst().onePairDays()).isNull();
        assertThat(dto.analysis().getFirst().bonus()).isFalse();
        assertThat(dto.analysis().getFirst().scopeMatchCount()).isNull();
        assertThat(dto.hardScore()).isNull();
        assertThat(dto.outcome()).isNull();
    }

    /** genfix §5: a plan stored before {@code initial} and {@code pinnedInBase} reads with the old pin rule. */
    @Test
    void anOldPlanReadsWithoutTheBaseFields() {
        GenerationJob job = new GenerationJob(UUID.randomUUID(), jsonMapper.readTree("{}"), "admin");
        job.setPlan(jsonMapper.readTree("""
                {"units":[{"unitId":"pin:1","loadItemId":null,"groupId":"%s","subgroup":0,"disciplineId":"%s",
                 "teacherId":null,"weight":"WHOLE","lessonKind":"MIXED","requiredRoomType":null,"preferredRoomId":null,
                 "streamId":null,"online":false,"pinned":{"weekday":1,"pair":2,"parity":"BOTH","roomId":null},
                 "sourceLessonId":"%s"}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));

        PlanUnit unit = mapper.plan(job).units().getFirst();

        assertThat(unit.initial()).isNull();
        assertThat(unit.pinnedInBase()).isNull();
        assertThat(unit.pinnedInDraft()).isTrue();
    }

    @Test
    void aListOfJobsReadsItsGroupsOnce() {
        StudentGroup ki1 = new StudentGroup("4КІ1", 4, UUID.randomUUID());
        given(groups.findAllById(any())).willReturn(List.of(ki1));
        GenerationJob first = new GenerationJob(UUID.randomUUID(),
                jsonMapper.readTree("{\"groupIds\":[\"%s\"]}".formatted(ki1.getId())), "admin");
        GenerationJob second = new GenerationJob(UUID.randomUUID(),
                jsonMapper.readTree("{\"groupIds\":[\"%s\"]}".formatted(ki1.getId())), "admin");

        List<GenerationJobDto> dtos = mapper.toDtos(List.of(first, second));

        assertThat(dtos).allSatisfy(dto -> assertThat(dto.groups()).extracting(GroupRef::code).containsExactly("4КІ1"));
        verify(groups, times(1)).findAllById(any());
    }

    @Test
    void outcomeIsReadBack() {
        GroupRef group = new GroupRef(UUID.randomUUID(), "4КІ1", 4);
        GenerationJob job = new GenerationJob(UUID.randomUUID(), jsonMapper.readTree("{}"), "admin");
        GenerationOutcome outcome = new GenerationOutcome(3, 1, 18, List.of(new GenerationOutcome.GroupOutcome(group,
                3, 18)), 2, 1, 40);
        job.setOutcome(mapper.json(outcome));

        assertThat(mapper.toDto(job).outcome()).isEqualTo(outcome);
    }

    @Test
    void outcomeStoredBeforeRoomAssignedReadsAsUnknown() {
        GenerationJob job = new GenerationJob(UUID.randomUUID(), jsonMapper.readTree("{}"), "admin");
        job.setOutcome(jsonMapper.readTree("{\"moved\":3,\"roomChanged\":1,\"total\":18,\"perGroup\":[]}"));

        assertThat(mapper.toDto(job).outcome()).isEqualTo(new GenerationOutcome(3, 1, 18, List.of()));
    }

    @Test
    void scoreTextIsParsed() {
        assertThat(GenerationJobMapper.parseScore("-3hard/-1200soft")).containsExactly(-3L, -1200L);
        assertThat(GenerationJobMapper.parseScore("0hard/15soft")).containsExactly(0L, 15L);
        assertThat(GenerationJobMapper.parseScore(null)).containsExactly(null, null);
        assertThat(GenerationJobMapper.parseScore("0hard")).containsExactly(null, null);
    }
}
