package ua.vtc.rozklad.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventJsonRoundTripTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<RozkladEvent> events() {
        return EventSamples.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void everyEventRoundTripsThroughJson(RozkladEvent event) {
        String json = mapper.writeValueAsString(event);

        RozkladEvent restored = mapper.readValue(json, event.getClass());

        assertThat(restored).isEqualTo(event);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void envelopeIsFlatAndUsesIsoInstants(RozkladEvent event) {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-18T15:30:00Z");
        assertThat(json.get("version").asInt()).isEqualTo(RozkladEvents.SCHEMA_VERSION);
    }

    @Test
    void samplesCoverEveryEventType() {
        Set<Class<?>> sampled = EventSamples.all().stream().map(Object::getClass).collect(Collectors.toSet());

        assertThat(sampled).containsExactlyInAnyOrder(RozkladEvent.class.getPermittedSubclasses());
    }

    @Test
    void datesAreIsoStrings() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(EventSamples.all().get(1)));

        assertThat(json.get("dates").get(0).asString()).isEqualTo("2026-10-19");
    }

    @Test
    void missingListsBecomeEmptyButNullPairsMeanWholeDay() {
        String json = """
                {"eventId":"8b9c0d1e-2f3a-4b8c-9d0e-1f2a3b4c5d6e","occurredAt":"2026-10-18T15:30:00Z","version":1,
                 "reason":"CALENDAR_CHANGED"}
                """;

        ScheduleChanged changed = mapper.readValue(json, ScheduleChanged.class);

        assertThat(changed.dates()).isEmpty();
        assertThat(changed.groupIds()).isEmpty();
        assertThat(changed.teacherIds()).isEmpty();

        AbsenceReported wholeDay = (AbsenceReported) EventSamples.all().get(11);
        AbsenceReported restored = mapper.readValue(mapper.writeValueAsString(wholeDay), AbsenceReported.class);
        assertThat(restored.pairs()).isNull();
        assertThat(restored.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    /** Round 7 fields are additive: a request of the first schema reads as a CONFIRM request without details. */
    @Test
    void confirmationRequestOfTheFirstSchemaIsAConfirmRequest() {
        String json = """
                {"eventId":"8b9c0d1e-2f3a-4b8c-9d0e-1f2a3b4c5d6e","occurredAt":"2026-10-18T15:30:00Z","version":1,
                 "changeId":"7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d","teacherId":"3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f",
                 "date":"2026-10-19","pair":2,"groupCode":"4КІ1","subgroup":0,"disciplineName":"СТП",
                 "deadline":"2026-10-18T17:00:00Z"}
                """;

        SubstitutionConfirmationRequested request = mapper.readValue(json, SubstitutionConfirmationRequested.class);

        assertThat(request.mode()).isNull();
        assertThat(request.effectiveMode()).isEqualTo(ua.vtc.rozklad.contracts.SubstitutionRequestMode.CONFIRM);
        assertThat(request.kind()).isNull();
        assertThat(request.originalDisciplineName()).isNull();
        assertThat(request.reasonCode()).isNull();
    }

    /** Round 7: {@code teacherRoomId} is additive; a unit of an older snapshot reads without it. */
    @Test
    void snapshotUnitWithoutTeacherRoomReadsAsNull() {
        String json = """
                {"unitId":"u-1","groupId":"3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f",
                 "disciplineId":"7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d","subgroup":0,"weight":"WHOLE",
                 "homeRoomId":"8b9c0d1e-2f3a-4b8c-9d0e-1f2a3b4c5d6e"}
                """;

        ProblemSnapshot.Unit unit = mapper.readValue(json, ProblemSnapshot.Unit.class);

        assertThat(unit.teacherRoomId()).isNull();
        assertThat(unit.homeRoomId()).hasToString("8b9c0d1e-2f3a-4b8c-9d0e-1f2a3b4c5d6e");
        assertThat(unit.initial()).isNull();
    }

    @Test
    void objectionIsADeclineOfAnAppliedChange() {
        SubstitutionTeacherReplied objection = (SubstitutionTeacherReplied) EventSamples.all().get(9);

        assertThat(objection.raisesObjection()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(objection), SubstitutionTeacherReplied.class)
                .raisesObjection()).isTrue();
    }
}
