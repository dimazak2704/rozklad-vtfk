package ua.vtc.rozklad.scheduler.solver.score;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Constraints of the generator. The solver identifies a constraint by an ASCII {@link #id()}
 * (Timefold accepts only Latin letters there); users see the Ukrainian {@link #title()} in the
 * score analysis of {@code generation.completed}.
 */
public enum TimetableConstraint {

    TEACHER_CONFLICT("Teacher conflict", "Накладка у викладача", true),
    COHORT_CONFLICT("Group conflict", "Накладка в розкладі групи", true),
    ROOM_CONFLICT("Room conflict", "Накладка в аудиторії", true),
    ROOM_TYPE("Room type mismatch", "Невідповідний тип аудиторії", true),
    ROOM_CAPACITY("Room too small", "Аудиторія замала для групи", true),
    OUTSIDE_SHIFT("Outside shift window", "Пара поза межами зміни", true),
    TEACHER_UNAVAILABLE("Teacher unavailable", "Викладач недоступний у цей час", true),

    GROUP_WINDOWS("Group windows", "Вікна в розкладі групи", false),
    TEACHER_WINDOWS("Teacher windows", "Вікна у викладача", false),
    BROKEN_DAY("Broken day", "Рваний день (чисельник і знаменник різні)", false),
    LATE_PAIR("Late pair", "Пізні пари в межах зміни", false),
    DISCIPLINE_REPEAT("Discipline repeated", "Дисципліна три й більше разів на день", false),
    UNWANTED_SLOT("Unwanted slot", "Небажаний для викладача час", false),
    ROOM_PREFERENCE("Room preference", "Заняття не у своїй аудиторії", false),
    HALF_PAIRING("Halves paired", "Чисельник і знаменник в одному слоті (бонус)", false);

    private static final Map<String, TimetableConstraint> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TimetableConstraint::id, Function.identity()));

    private final String id;
    private final String title;
    private final boolean hard;

    TimetableConstraint(String id, String title, boolean hard) {
        this.id = id;
        this.title = title;
        this.hard = hard;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public boolean isHard() {
        return hard;
    }

    public static TimetableConstraint byId(String id) {
        TimetableConstraint constraint = BY_ID.get(id);
        if (constraint == null) {
            throw new IllegalArgumentException("Unknown constraint id: " + id);
        }
        return constraint;
    }
}
