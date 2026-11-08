package ua.vtc.rozklad.contracts.events;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.UnitWeight;

/** Realistic sample of every event type (data from order №35, 19.10.2026). */
final class EventSamples {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T15:30:00Z"), ZoneOffset.UTC);

    private static final UUID SEMESTER = UUID.fromString("0f9d8c1e-2a3b-4c5d-8e6f-7a8b9c0d1e2f");
    private static final UUID GROUP_4KI1 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");
    private static final UUID TEACHER_KRENTSIN = UUID.fromString("2b3c4d5e-6f7a-4b2c-9d3e-4f5a6b7c8d9e");
    private static final UUID TEACHER_NEPYIVODA = UUID.fromString("3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f");
    private static final UUID ROOM_6 = UUID.fromString("4d5e6f7a-8b9c-4d4e-9f5a-6b7c8d9e0f1a");
    private static final UUID DISCIPLINE_STP = UUID.fromString("5e6f7a8b-9c0d-4e5f-8a6b-7c8d9e0f1a2b");
    private static final UUID JOB = UUID.fromString("6f7a8b9c-0d1e-4f6a-9b7c-8d9e0f1a2b3c");
    private static final UUID CHANGE = UUID.fromString("7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    private EventSamples() {
    }

    static List<RozkladEvent> all() {
        return List.of(
                timetableVersionPublished(),
                scheduleChanged(),
                generationRequested(),
                new GenerationCancelRequested(envelope(), JOB),
                new GenerationProgress(envelope(), JOB, 4_000, -2, -340),
                generationCompleted(),
                substitutionConfirmationRequested(),
                substitutionNotice(),
                new SubstitutionStatusChanged(envelope(), CHANGE, ChangeStatus.CONFIRMED, TEACHER_NEPYIVODA, DATE,
                        "4КІ1"),
                new SubstitutionTeacherReplied(envelope(), CHANGE, TEACHER_NEPYIVODA, ConfirmationDecision.DECLINE,
                        ChangeStatus.APPLIED, DATE, 2, "4КІ1"),
                absenceReported(List.of(1, 2)),
                absenceReported(null),
                orderPublished());
    }

    static EventEnvelope envelope() {
        return EventEnvelope.create(CLOCK);
    }

    private static TimetableVersionPublished timetableVersionPublished() {
        return new TimetableVersionPublished(envelope(), UUID.randomUUID(), SEMESTER, LocalDate.of(2026, 9, 1),
                "Базовий розклад I семестр 2026/2027");
    }

    private static ScheduleChanged scheduleChanged() {
        return new ScheduleChanged(envelope(), List.of(DATE, DATE.plusDays(1)), List.of(GROUP_4KI1),
                List.of(TEACHER_KRENTSIN, TEACHER_NEPYIVODA), ScheduleChangeReason.ORDER_PUBLISHED);
    }

    private static GenerationRequested generationRequested() {
        var slot = new ProblemSnapshot.Timeslot(3, 2);
        var problem = new ProblemSnapshot(
                List.of(new ProblemSnapshot.Timeslot(1, 1), slot),
                List.of(new ProblemSnapshot.ShiftWindow(1, 1, 1, 4), new ProblemSnapshot.ShiftWindow(2, 5, 4, 6)),
                List.of(new ProblemSnapshot.Group(GROUP_4KI1, "4КІ1", 25, 1, List.of(1, 2))),
                List.of(new ProblemSnapshot.Room(ROOM_6, "6", RoomType.COMPUTER_LAB, 30, 1)),
                List.of(new ProblemSnapshot.Teacher(TEACHER_KRENTSIN, "Кренцін М.Д.", List.of(slot), List.of())),
                List.of(
                        new ProblemSnapshot.Unit("u-1", UUID.randomUUID(), GROUP_4KI1, 0, DISCIPLINE_STP,
                                TEACHER_KRENTSIN, UnitWeight.WHOLE, RoomType.COMPUTER_LAB, ROOM_6, ROOM_6, null),
                        new ProblemSnapshot.Unit("u-2", UUID.randomUUID(), GROUP_4KI1, 1, DISCIPLINE_STP, null,
                                UnitWeight.HALF, null, null, null,
                                new ProblemSnapshot.PinnedSlot(3, 2, Parity.NUMERATOR, null))));
        return new GenerationRequested(envelope(), JOB, SEMESTER, 120, new GenerationWeights(10, 8, 3, 5, 1),
                problem);
    }

    private static GenerationCompleted generationCompleted() {
        return new GenerationCompleted(envelope(), JOB, GenerationStatus.SOLVED, 0, -35,
                List.of(new GenerationCompleted.Assignment("u-1", 1, 2, Parity.BOTH, ROOM_6),
                        new GenerationCompleted.Assignment("u-2", 3, 2, Parity.NUMERATOR, null)),
                List.of(new GenerationCompleted.ConstraintScore("Вікна в групи", 3, "0hard/-30soft")),
                null);
    }

    private static SubstitutionConfirmationRequested substitutionConfirmationRequested() {
        return new SubstitutionConfirmationRequested(envelope(), CHANGE, TEACHER_NEPYIVODA, DATE, 2,
                LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                Instant.parse("2026-10-18T17:00:00Z"));
    }

    /** NOTIFY: a REPLACE_DISCIPLINE change the teacher is informed about (no deadline). */
    private static SubstitutionConfirmationRequested substitutionNotice() {
        return new SubstitutionConfirmationRequested(envelope(), CHANGE, TEACHER_NEPYIVODA, DATE, 2,
                LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6", null,
                SubstitutionRequestMode.NOTIFY, ChangeKind.REPLACE_DISCIPLINE, "Сучасні технології проєктування",
                "MUTUAL");
    }

    private static AbsenceReported absenceReported(List<Integer> pairs) {
        return new AbsenceReported(envelope(), UUID.randomUUID(), TEACHER_KRENTSIN, "Кренцін М.Д.", DATE, DATE,
                pairs, AbsenceSource.BOT);
    }

    private static OrderPublished orderPublished() {
        var replacement = new OrderPayload.Item(UUID.randomUUID(), "4КІ1", "2", OrderRowType.REPLACEMENT, "СТП",
                "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6", CHANGE);
        var cancellation = new OrderPayload.Item(UUID.randomUUID(), "1ТК1", "1", OrderRowType.CANCELLATION,
                "Ін. мова", "Крисак Л. (п.)", "Відп.", null, null, null, null);
        var roomChange = new OrderPayload.Item(UUID.randomUUID(), "2КІ1", "3", OrderRowType.ROOM_CHANGE, null, null,
                null, null, null, "304/309", null);
        var order = new OrderPayload(UUID.randomUUID(), 35, DATE, OrderKind.ORDER, null, OrderStatus.PUBLISHED,
                "По коледжу про деякі зміни в розкладі занять", "ПОНЕДІЛОК", "ЗНАМЕННИК",
                List.of(replacement, cancellation), List.of(roomChange),
                List.of("Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."),
                "Заступник директора з НР", "Микола ПОЛІЩУК", "I семестр 2026/2027",
                List.of(new OrderPayload.AffectedGroup(GROUP_4KI1, "4КІ1")),
                List.of(new OrderPayload.AffectedTeacher(TEACHER_NEPYIVODA, "Непийвода")));
        return new OrderPublished(envelope(), order);
    }
}
