package ua.vtc.rozklad.scheduler.solver.score;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Constraints of the generator. The solver identifies a constraint by an ASCII {@link #id()}
 * (Timefold accepts only Latin letters there); users see the Ukrainian {@link #title()} in the
 * score analysis of {@code generation.completed}. Hard rules come first; {@link #isBonus()} marks the
 * one reward.
 */
public enum TimetableConstraint {

    TEACHER_CONFLICT("Teacher conflict", "Накладка у викладача", Kind.HARD),
    COHORT_CONFLICT("Group conflict", "Накладка в розкладі групи", Kind.HARD),
    ROOM_CONFLICT("Room conflict", "Накладка в аудиторії", Kind.HARD),
    ROOM_TYPE("Room type mismatch", "Невідповідний тип аудиторії", Kind.HARD),
    ROOM_CAPACITY("Room too small", "Аудиторія замала для групи", Kind.HARD),
    OUTSIDE_SHIFT("Outside shift window", "Пара поза межами зміни", Kind.HARD),
    TEACHER_UNAVAILABLE("Teacher unavailable", "Викладач недоступний у цей час", Kind.HARD),

    GROUP_WINDOWS("Group windows", "Вікна в розкладі групи", Kind.PENALTY),
    TEACHER_WINDOWS("Teacher windows", "Вікна у викладача", Kind.PENALTY),
    TEACHER_SHIFT_WAIT("Teacher shift wait", "Очікування викладача між змінами", Kind.PENALTY),
    BROKEN_DAY("Broken day", "Рваний день (чисельник і знаменник різні)", Kind.PENALTY),
    LATE_PAIR("Late pair", "4-та пара зміни", Kind.PENALTY),
    GROUP_ONE_PAIR_DAY("Group one pair day", "День з однією парою в групи", Kind.PENALTY),
    SUBGROUP_ALONE("Subgroup alone", "Підгрупа на парі, а інша без заняття", Kind.PENALTY),
    SIBLINGS_APART("Subgroups apart", "Підгрупи одного викладача не підряд", Kind.PENALTY),
    TEACHER_ONE_PAIR_DAY("Teacher one pair day", "День з однією парою у викладача", Kind.PENALTY),
    TEACHER_EXTRA_DAY("Teacher extra day", "Зайвий робочий день викладача", Kind.PENALTY),
    TEACHER_OVERLOAD("Teacher overload", "Забагато пар на день у викладача", Kind.PENALTY),
    DISCIPLINE_TWICE("Discipline twice", "Дисципліна двічі на день", Kind.PENALTY),
    DISCIPLINE_REPEAT("Discipline repeated", "Дисципліна три й більше разів на день", Kind.PENALTY),
    UNWANTED_SLOT("Unwanted slot", "Небажаний для викладача час", Kind.PENALTY),
    ROOM_PREFERENCE("Room preference", "Заняття не в аудиторії викладача", Kind.PENALTY),
    UNIT_MOVED("Unit moved", "Пару переставлено відносно базової версії", Kind.PENALTY),
    ROOM_CHANGED("Room changed", "Пара в іншій аудиторії, ніж у базовій версії", Kind.PENALTY),
    SPECIAL_ROOM_LOST("Special room lost", "Пара не в спортзалі чи на спортмайданчику, як у базовій версії",
            Kind.PENALTY),
    HALF_PAIRING("Halves paired", "Чисельник і знаменник в одному слоті (бонус)", Kind.BONUS);

    private static final Map<String, TimetableConstraint> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TimetableConstraint::id, Function.identity()));

    private final String id;
    private final String title;
    private final Kind kind;

    TimetableConstraint(String id, String title, Kind kind) {
        this.id = id;
        this.title = title;
        this.kind = kind;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public boolean isHard() {
        return kind == Kind.HARD;
    }

    /** A soft reward (counted as achieved, not as a remaining problem). */
    public boolean isBonus() {
        return kind == Kind.BONUS;
    }

    private enum Kind { HARD, PENALTY, BONUS }

    public static TimetableConstraint byId(String id) {
        TimetableConstraint constraint = BY_ID.get(id);
        if (constraint == null) {
            throw new IllegalArgumentException("Unknown constraint id: " + id);
        }
        return constraint;
    }
}
