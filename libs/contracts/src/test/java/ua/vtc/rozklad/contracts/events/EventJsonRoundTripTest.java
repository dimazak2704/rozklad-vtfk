package ua.vtc.rozklad.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
        assertThat(unit.online()).as("older snapshots have no online flag").isFalse();
    }

    /** {@code online} is additive: an online pinned lesson keeps its room for reference only (DOMAIN §5). */
    @Test
    void snapshotUnitCarriesTheOnlineFlag() {
        ProblemSnapshot.PinnedSlot slot = new ProblemSnapshot.PinnedSlot(1, 1, ua.vtc.rozklad.contracts.Parity.BOTH,
                java.util.UUID.fromString("8b9c0d1e-2f3a-4b8c-9d0e-1f2a3b4c5d6e"));
        ProblemSnapshot.Unit unit = new ProblemSnapshot.Unit("pin:1", null,
                java.util.UUID.fromString("3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f"), 0,
                java.util.UUID.fromString("7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d"), null,
                ua.vtc.rozklad.contracts.UnitWeight.WHOLE, null, null, null, slot, null, null, true);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(unit));
        ProblemSnapshot.Unit restored = mapper.readValue(json.toString(), ProblemSnapshot.Unit.class);

        assertThat(json.get("online").asBoolean()).isTrue();
        assertThat(restored).isEqualTo(unit);
        assertThat(new ProblemSnapshot.Unit("u", null, unit.groupId(), 0, unit.disciplineId(), null,
                unit.weight(), null, null, null, null, null, null).online())
                .as("the 13-argument constructor stays and means in person").isFalse();
    }

    /** genfix: the 8 new weights are additive; a request of the 5-weight schema reads with defaults. */
    @Test
    void weightsOfTheFiveFieldSchemaReadWithDefaultsAndTeacherWindowsFollowWindows() {
        String json = """
                {"windows":7,"brokenDays":10,"latePairs":3,"preferences":5,"homeRoom":1}
                """;

        GenerationWeights weights = mapper.readValue(json, GenerationWeights.class);

        assertThat(weights.teacherWindows()).as("one weight covered both before").isEqualTo(7);
        assertThat(weights.teacherShiftWait()).isEqualTo(GenerationWeights.DEFAULT_TEACHER_SHIFT_WAIT);
        assertThat(weights.stability()).isEqualTo(GenerationWeights.DEFAULT_STABILITY);
        assertThat(weights.subgroupsTogether()).isEqualTo(GenerationWeights.DEFAULT_SUBGROUPS_TOGETHER);
        assertThat(weights.studentOnePairDay()).isEqualTo(GenerationWeights.DEFAULT_STUDENT_ONE_PAIR_DAY);
        assertThat(weights.teacherDays()).isEqualTo(GenerationWeights.DEFAULT_TEACHER_DAYS);
        assertThat(weights.teacherOverload()).isEqualTo(GenerationWeights.DEFAULT_TEACHER_OVERLOAD);
        assertThat(weights.disciplineTwice()).isEqualTo(GenerationWeights.DEFAULT_DISCIPLINE_TWICE);
        assertThat(weights.specialRooms()).isEqualTo(GenerationWeights.DEFAULT_SPECIAL_ROOMS);
        assertThat(weights).isEqualTo(new GenerationWeights(7, 10, 3, 5, 1));
    }

    /** genfix repair: {@code specialRooms} is additive to the 13-weight schema; absent → default, 0 switches it off. */
    @Test
    void weightsOfTheThirteenFieldSchemaReadWithTheSpecialRoomsDefault() {
        String json = """
                {"windows":30,"brokenDays":10,"latePairs":0,"preferences":5,"homeRoom":1,"teacherWindows":10,
                 "teacherShiftWait":3,"stability":1,"subgroupsTogether":30,"studentOnePairDay":10,"teacherDays":5,
                 "teacherOverload":3,"disciplineTwice":10}
                """;

        GenerationWeights weights = mapper.readValue(json, GenerationWeights.class);

        assertThat(weights.specialRooms()).isEqualTo(GenerationWeights.DEFAULT_SPECIAL_ROOMS);
        assertThat(weights).isEqualTo(GenerationWeights.DEFAULT);
        assertThat(mapper.readValue(json.replace("}", ",\"specialRooms\":0}"), GenerationWeights.class).specialRooms())
                .as("0 switches the rule off").isZero();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenerationWeights(10, 10, 3, 5, 1, null, null, null, null, null, null, null, null, -1));
    }

    @Test
    void weightsDefaultIsCompleteAndNegativeWeightsAreRefused() {
        assertThat(GenerationWeights.DEFAULT)
                .as("owner, 15.11.2026: the 4th pair is normal (latePairs 0), subgroups together the strongest (30)")
                .isEqualTo(new GenerationWeights(30, 10, 0, 5, 1, 10, 3, 1, 30, 10, 5, 3, 10, 30));
        assertThat(new GenerationWeights(10, 10, 3, 5, 1, 0, null, 0, null, null, null, null, null))
                .satisfies(weights -> {
                    assertThat(weights.teacherWindows()).as("an explicit 0 switches teacher windows off").isZero();
                    assertThat(weights.stability()).isZero();
                    assertThat(weights.subgroupsTogether()).isEqualTo(GenerationWeights.DEFAULT_SUBGROUPS_TOGETHER);
                });
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenerationWeights(10, 10, 3, 5, 1, null, null, -1, null, null, null, null, null));
    }

    /** genfix: {@code maxPairsPerDay} and {@code siblingKey} are additive; older snapshots read without them. */
    @Test
    void snapshotTeacherAndUnitOfTheOldSchemaReadWithoutTheNewFields() {
        String teacherJson = """
                {"id":"3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f","name":"Кренцін М.Д.","unavailable":[],"unwanted":[]}
                """;
        String unitJson = """
                {"unitId":"u-1","groupId":"3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f",
                 "disciplineId":"7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d","subgroup":1,"weight":"WHOLE",
                 "teacherRoomId":null,"online":false}
                """;

        ProblemSnapshot.Teacher teacher = mapper.readValue(teacherJson, ProblemSnapshot.Teacher.class);
        ProblemSnapshot.Unit unit = mapper.readValue(unitJson, ProblemSnapshot.Unit.class);

        assertThat(teacher.maxPairsPerDay()).as("no limit").isNull();
        assertThat(unit.siblingKey()).isNull();
        assertThat(unit.online()).isFalse();
    }

    /** Owner's rule of 15.11.2026: {@code fridayMaxPairs} is additive; an older group reads without a limit. */
    @Test
    void snapshotGroupReadsWithAndWithoutTheFridayLimit() {
        String old = """
                {"id":"3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f","code":"4КІ1","size":25,"shift":1,"subgroups":[1,2]}
                """;
        ProblemSnapshot.Group group = mapper.readValue(old, ProblemSnapshot.Group.class);
        assertThat(group.fridayMaxPairs()).as("no limit").isNull();

        ProblemSnapshot.Group limited = new ProblemSnapshot.Group(group.id(), "4КІ1", 25, 1, List.of(1, 2), 3);
        assertThat(mapper.readValue(mapper.writeValueAsString(limited), ProblemSnapshot.Group.class))
                .isEqualTo(limited);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProblemSnapshot.Group(group.id(), "4КІ1", 25, 1, List.of(), 0));
    }

    /** genfix: an analysis row of the first schema reads as a non-bonus row with unknown scope matches. */
    @Test
    void constraintScoreOfTheFirstSchemaIsNotABonus() {
        String json = """
                {"constraint":"Вікна груп","matchCount":3,"score":"0hard/-30soft"}
                """;

        GenerationCompleted.ConstraintScore row = mapper.readValue(json, GenerationCompleted.ConstraintScore.class);

        assertThat(row.bonus()).isFalse();
        assertThat(row.constraintId()).isNull();
        assertThat(row.scopeMatchCount()).isNull();
        assertThat(row).isEqualTo(new GenerationCompleted.ConstraintScore("Вікна груп", 3, "0hard/-30soft"));
    }

    /** Auto mode is additive: a request without {@code timeLimitSeconds} (or with null) has no limit. */
    @Test
    void generationRequestWithoutTimeLimitIsAutoModeAndOldRequestsKeepTheirLimit() {
        GenerationRequested fixed = (GenerationRequested) EventSamples.all().get(2);
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(fixed));

        assertThat(json.get("timeLimitSeconds").asInt()).isEqualTo(120);
        GenerationRequested old = mapper.readValue(json.toString(), GenerationRequested.class);
        assertThat(old.timeLimitSeconds()).isEqualTo(120);
        assertThat(old.autoMode()).isFalse();

        json.remove("timeLimitSeconds");
        GenerationRequested absent = mapper.readValue(json.toString(), GenerationRequested.class);
        assertThat(absent.timeLimitSeconds()).isNull();
        assertThat(absent.autoMode()).isTrue();

        json.putNull("timeLimitSeconds");
        assertThat(mapper.readValue(json.toString(), GenerationRequested.class).autoMode()).isTrue();
    }

    /** {@code stopReason} is additive: a completion of the first schema reads without it. */
    @Test
    void completionOfTheFirstSchemaHasNoStopReason() {
        GenerationCompleted completed = (GenerationCompleted) EventSamples.all().get(5);
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(completed));

        assertThat(json.get("stopReason").asString()).isEqualTo("NO_IMPROVEMENT");
        json.remove("stopReason");

        GenerationCompleted old = mapper.readValue(json.toString(), GenerationCompleted.class);
        assertThat(old.stopReason()).isNull();
        assertThat(old.assignments()).isEqualTo(completed.assignments());
    }

    @Test
    void objectionIsADeclineOfAnAppliedChange() {
        SubstitutionTeacherReplied objection = (SubstitutionTeacherReplied) EventSamples.all().get(9);

        assertThat(objection.raisesObjection()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(objection), SubstitutionTeacherReplied.class)
                .raisesObjection()).isTrue();
    }
}
