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

        AbsenceReported wholeDay = (AbsenceReported) EventSamples.all().get(9);
        AbsenceReported restored = mapper.readValue(mapper.writeValueAsString(wholeDay), AbsenceReported.class);
        assertThat(restored.pairs()).isNull();
        assertThat(restored.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 19));
    }
}
