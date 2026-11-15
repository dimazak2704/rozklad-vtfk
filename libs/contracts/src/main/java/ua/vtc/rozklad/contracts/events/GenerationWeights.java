package ua.vtc.rozklad.contracts.events;

/**
 * Soft constraint weights of a generation request (0 disables a constraint). The first five components
 * are the original schema; the rest are additive wrapper types: an absent (or {@code null}) one takes its
 * default, and an absent {@code teacherWindows} falls back to {@code windows}, which is how one weight
 * covered both group and teacher windows before. After construction no component is {@code null}.
 *
 * <p>Defaults (docs/CONTRACTS.md, "GenerationWeights") are justified by the college's real timetable and were
 * tuned by replaying the college's real generation requests ({@code SnapshotReplayTest} in the scheduler): with
 * them a whole-college run from the published version keeps every student metric at or below the published
 * timetable. The 5-argument constructor and old JSON keep {@code teacherWindows = windows}.
 *
 * <p>{@code specialRooms} (14th, genfix repair): a lesson whose load item stood only in special-purpose rooms (sports
 * hall, sports ground) in the base version is placed in another kind of room. It replaces core's derived required
 * room type, which was hard and could not be switched off.
 */
public record GenerationWeights(int windows, int brokenDays, int latePairs, int preferences, int homeRoom,
        Integer teacherWindows, Integer teacherShiftWait, Integer stability, Integer subgroupsTogether,
        Integer studentOnePairDay, Integer teacherDays, Integer teacherOverload, Integer disciplineTwice,
        Integer specialRooms) {

    // Declared before DEFAULT: static fields initialise in order and the compact constructor reads these.
    public static final int DEFAULT_TEACHER_SHIFT_WAIT = 3;
    public static final int DEFAULT_STABILITY = 1;
    public static final int DEFAULT_SUBGROUPS_TOGETHER = 10;
    public static final int DEFAULT_STUDENT_ONE_PAIR_DAY = 10;
    public static final int DEFAULT_TEACHER_DAYS = 5;
    public static final int DEFAULT_TEACHER_OVERLOAD = 3;
    public static final int DEFAULT_DISCIPLINE_TWICE = 10;
    public static final int DEFAULT_SPECIAL_ROOMS = 30;

    public static final GenerationWeights DEFAULT = new GenerationWeights(30, 10, 9, 5, 1, 10,
            DEFAULT_TEACHER_SHIFT_WAIT, DEFAULT_STABILITY, DEFAULT_SUBGROUPS_TOGETHER, DEFAULT_STUDENT_ONE_PAIR_DAY,
            DEFAULT_TEACHER_DAYS, DEFAULT_TEACHER_OVERLOAD, DEFAULT_DISCIPLINE_TWICE, DEFAULT_SPECIAL_ROOMS);

    public GenerationWeights {
        teacherWindows = teacherWindows == null ? Integer.valueOf(windows) : teacherWindows;
        teacherShiftWait = orDefault(teacherShiftWait, DEFAULT_TEACHER_SHIFT_WAIT);
        stability = orDefault(stability, DEFAULT_STABILITY);
        subgroupsTogether = orDefault(subgroupsTogether, DEFAULT_SUBGROUPS_TOGETHER);
        studentOnePairDay = orDefault(studentOnePairDay, DEFAULT_STUDENT_ONE_PAIR_DAY);
        teacherDays = orDefault(teacherDays, DEFAULT_TEACHER_DAYS);
        teacherOverload = orDefault(teacherOverload, DEFAULT_TEACHER_OVERLOAD);
        disciplineTwice = orDefault(disciplineTwice, DEFAULT_DISCIPLINE_TWICE);
        specialRooms = orDefault(specialRooms, DEFAULT_SPECIAL_ROOMS);
        if (windows < 0 || brokenDays < 0 || latePairs < 0 || preferences < 0 || homeRoom < 0
                || teacherWindows < 0 || teacherShiftWait < 0 || stability < 0 || subgroupsTogether < 0
                || studentOnePairDay < 0 || teacherDays < 0 || teacherOverload < 0 || disciplineTwice < 0
                || specialRooms < 0) {
            throw new IllegalArgumentException("weights must not be negative");
        }
    }

    /** The original five weights; the new ones take their defaults and teacher windows follow {@code windows}. */
    public GenerationWeights(int windows, int brokenDays, int latePairs, int preferences, int homeRoom) {
        this(windows, brokenDays, latePairs, preferences, homeRoom, null, null, null, null, null, null, null, null,
                null);
    }

    /** The 13 weights of the first genfix schema; {@code specialRooms} takes its default. */
    public GenerationWeights(int windows, int brokenDays, int latePairs, int preferences, int homeRoom,
                             Integer teacherWindows, Integer teacherShiftWait, Integer stability,
                             Integer subgroupsTogether, Integer studentOnePairDay, Integer teacherDays,
                             Integer teacherOverload, Integer disciplineTwice) {
        this(windows, brokenDays, latePairs, preferences, homeRoom, teacherWindows, teacherShiftWait, stability,
                subgroupsTogether, studentOnePairDay, teacherDays, teacherOverload, disciplineTwice, null);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? Integer.valueOf(fallback) : value;
    }
}
