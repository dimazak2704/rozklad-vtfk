package ua.vtc.rozklad.notification.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSlot;
import ua.vtc.rozklad.notification.core.CoreDtos.DisciplineRef;
import ua.vtc.rozklad.notification.core.CoreDtos.GroupRef;
import ua.vtc.rozklad.notification.core.CoreDtos.LessonItem;
import ua.vtc.rozklad.notification.core.CoreDtos.NamedRef;
import ua.vtc.rozklad.notification.core.CoreDtos.PairNow;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreDtos.RoomRef;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreDtos.SemesterInfo;
import ua.vtc.rozklad.notification.core.CoreDtos.TeacherRef;

/** Core read models modelled on the real 4КІ1 schedule and order №35 (19.10.2026, знаменник). */
public final class CoreFixtures {

    public static final UUID GROUP_4KI1 = Order35.GROUP_4KI1;
    public static final UUID GROUP_4KI2 = Order35.GROUP_4KI2;
    public static final UUID GROUP_1TK1 = Order35.GROUP_1TK1;
    public static final UUID GROUP_2KI3 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c90");
    public static final UUID TEACHER_KRENTSIN = Order35.TEACHER_KRENTSIN;
    public static final UUID TEACHER_NEPYIVODA = Order35.TEACHER_NEPYIVODA;
    public static final UUID ROOM_6 = UUID.fromString("4d5e6f7a-8b9c-4d4e-9f5a-6b7c8d9e0f1a");
    public static final UUID SEMESTER = UUID.fromString("0f9d8c1e-2a3b-4c5d-8e6f-7a8b9c0d1e2f");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CoreFixtures() {
    }

    public static List<PublicGroup> groups() {
        return List.of(
                new PublicGroup(GROUP_1TK1, "1ТК1", 1, 1),
                new PublicGroup(GROUP_2KI3, "2КІ3", 2, 2),
                new PublicGroup(GROUP_4KI1, "4КІ1", 4, 1),
                new PublicGroup(GROUP_4KI2, "4КІ2", 4, 1));
    }

    public static List<PublicTeacher> teachers() {
        return List.of(
                new PublicTeacher(TEACHER_KRENTSIN, "Кренцін М.Д.", "Кренцін"),
                new PublicTeacher(TEACHER_NEPYIVODA, "Непийвода М.В.", "Непийвода"));
    }

    public static PublicMeta meta(LocalDate today) {
        return new PublicMeta("ВТФК", new SemesterInfo(SEMESTER, "I семестр 2026/2027", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 30)), today, 8, Parity.DENOMINATOR, today.getDayOfWeek().getValue() <= 5,
                new PairNow(2, LocalTime.of(9, 40), LocalTime.of(10, 40), 23),
                new PairNow(3, LocalTime.of(11, 0), LocalTime.of(12, 0), null),
                new NamedRef(UUID.randomUUID(), "2 зміни, 60 хв"));
    }

    public static BellSchedule bells(int weekday) {
        List<BellSlot> slots = weekday == 5
                ? List.of(slot(1, "08:30", "09:30"), slot(2, "09:40", "10:40"), slot(3, "11:00", "12:00"),
                slot(4, "12:20", "13:20"))
                : List.of(slot(1, "08:30", "09:30"), slot(2, "09:40", "10:40"), slot(3, "11:00", "12:00"),
                slot(4, "12:10", "13:10"));
        return new BellSchedule(new NamedRef(UUID.randomUUID(), "2 зміни, 60 хв"), weekday, slots);
    }

    /** 4КІ1 on 19.10.2026: a regular pair, the substitution of order №35 and a cancelled subgroup pair. */
    public static ScheduleDay day4ki1(LocalDate date) {
        GroupRef group = new GroupRef(GROUP_4KI1, "4КІ1", 4);
        RoomRef room6 = new RoomRef(ROOM_6, "6", "COMPUTER_LAB");
        return new ScheduleDay(date, date.getDayOfWeek().getValue(), "ПОНЕДІЛОК", Parity.DENOMINATOR, true,
                "Групи 4КІ навчаються дистанційно.", List.of(
                lesson(1, "08:30", "09:30", discipline("Комп. мережі"), teacher(null, "Колесник А.І."), room6, group,
                        0, "REGULAR", null, null),
                lesson(2, "09:40", "10:40", discipline("НДКС"), teacher(TEACHER_NEPYIVODA, "Непийвода М.В."), room6,
                        group, 0, "SUBSTITUTED",
                        json("{\"discipline\":{\"shortName\":\"СТП\"},\"teacher\":{\"shortName\":\"Кренцін М.Д.\"}}"),
                        "Зв.зам."),
                lesson(3, "11:00", "12:00", discipline("Ін.мова"), teacher(null, "Крисак Л."),
                        new RoomRef(UUID.randomUUID(), "110", "CLASSROOM"), group, 1, "CANCELLED", null, null)));
    }

    /** Непийвода on 19.10.2026: the substitution in 4КІ1. */
    public static ScheduleDay teacherDay(LocalDate date) {
        return new ScheduleDay(date, date.getDayOfWeek().getValue(), "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null,
                List.of(lesson(2, "09:40", "10:40", discipline("НДКС"), teacher(TEACHER_NEPYIVODA, "Непийвода М.В."),
                        new RoomRef(ROOM_6, "6", "COMPUTER_LAB"), new GroupRef(GROUP_4KI1, "4КІ1", 4), 0,
                        "SUBSTITUTED", json("{\"discipline\":\"СТП\",\"teacher\":\"Кренцін М.Д.\"}"), "Зв.зам.")));
    }

    private static LessonItem lesson(int pair, String start, String end, DisciplineRef discipline, TeacherRef teacher,
                                     RoomRef room, GroupRef group, int subgroup, String kind, JsonNode original,
                                     String reason) {
        return new LessonItem(UUID.randomUUID(), pair, LocalTime.parse(start), LocalTime.parse(end), discipline,
                teacher, room, group, subgroup, kind, false, original, reason, null);
    }

    private static DisciplineRef discipline(String shortName) {
        return new DisciplineRef(UUID.randomUUID(), shortName, shortName, "#336699");
    }

    private static TeacherRef teacher(UUID id, String shortName) {
        return new TeacherRef(id == null ? UUID.randomUUID() : id, shortName, shortName);
    }

    private static BellSlot slot(int pair, String start, String end) {
        return new BellSlot(pair, LocalTime.parse(start), LocalTime.parse(end));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
