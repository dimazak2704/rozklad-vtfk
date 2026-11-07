package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.effect.DatedSlotChecker;
import ua.vtc.rozklad.core.substitution.internal.effect.PlannedLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Candidate;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.MakeUpSlot;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Target;

/**
 * Ranks the ways to handle a lesson whose teacher is absent (DOMAIN §7) — a pure function.
 *
 * <p>REPLACE_TEACHER, scored exactly as DOMAIN §7: a candidate must not be the absent teacher,
 * absent or busy in the slot, or UNAVAILABLE; +50 teaches the discipline, +20 same cycle commission,
 * +15 closes a window, +10 already has lessons that day, −8 per substitution this month (at most
 * −24), −15 UNWANTED slot; top 10.
 *
 * <p>The other kinds use documented base scores so that one list can be ranked:
 * REPLACE_DISCIPLINE 40 (+20 behind plan, day factors as above, finished disciplines excluded,
 * top 5); SWAP 45 (+10 when the moved pair is the group's last one, −15 when it leaves a window);
 * CANCEL 20 (+15 for the first or last pair, −10 in the middle of the day); ADDITIONAL (make-up
 * lesson) 15. Ties: kind order, then title.
 */
public final class OptionRanker {

    public static final int TEACHES_DISCIPLINE = 50;
    public static final int SAME_COMMISSION = 20;
    public static final int CLOSES_WINDOW = 15;
    public static final int HAS_LESSONS_THAT_DAY = 10;
    public static final int PER_SUBSTITUTION = 8;
    public static final int MAX_SUBSTITUTION_PENALTY = 24;
    public static final int UNWANTED_SLOT = 15;
    public static final int TEACHER_LIMIT = 10;

    public static final int DISCIPLINE_BASE = 40;
    public static final int BEHIND_PLAN = 20;
    public static final int DISCIPLINE_LIMIT = 5;
    public static final int SWAP_BASE = 45;
    public static final int SWAP_LAST_PAIR = 10;
    public static final int SWAP_LEAVES_WINDOW = 15;
    public static final int CANCEL_BASE = 20;
    public static final int CANCEL_EDGE_PAIR = 15;
    public static final int CANCEL_MIDDLE_PAIR = 10;
    public static final int MAKE_UP_BASE = 15;

    private static final List<ChangeKind> KIND_ORDER = List.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.SWAP, ChangeKind.CANCEL, ChangeKind.ADDITIONAL);

    private OptionRanker() {
    }

    public static List<RankedOption> rank(RankingInput input) {
        Comparator<String> alphabetical = UkrainianText.collator();
        List<RankedOption> options = new ArrayList<>();
        options.addAll(replaceTeacher(input, alphabetical));
        options.addAll(replaceDiscipline(input, alphabetical));
        options.addAll(swap(input));
        options.add(cancel(input));
        options.addAll(makeUp(input));
        return options.stream()
                .sorted(Comparator.comparingInt(RankedOption::rawScore).reversed()
                        .thenComparingInt(option -> KIND_ORDER.indexOf(option.kind()))
                        .thenComparing(RankedOption::title, alphabetical))
                .toList();
    }

    static List<RankedOption> replaceTeacher(RankingInput input, Comparator<String> alphabetical) {
        Target target = input.target();
        record Scored(Candidate candidate, Score score, int substitutions) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : input.teachers()) {
            if (!available(candidate.id(), input)) {
                continue;
            }
            Score score = new Score(0);
            if (input.competentTeacherIds().contains(candidate.id())) {
                score.add(TEACHES_DISCIPLINE, "Веде цю дисципліну");
            }
            if (target.teacherCommissionId() != null
                    && target.teacherCommissionId().equals(candidate.cycleCommissionId())) {
                score.add(SAME_COMMISSION, "Та сама циклова комісія");
            }
            dayFactors(candidate.id(), input, score);
            int substitutions = input.substitutionsThisMonth().getOrDefault(candidate.id(), 0);
            if (substitutions > 0) {
                score.add(-Math.min(PER_SUBSTITUTION * substitutions, MAX_SUBSTITUTION_PENALTY),
                        substitutions + " " + UkrainianText.plural(substitutions, "заміна", "заміни", "замін")
                                + " цього місяця");
            }
            unwanted(candidate.id(), input, score);
            scored.add(new Scored(candidate, score, substitutions));
        }
        return scored.stream()
                .sorted(Comparator.comparingInt((Scored entry) -> entry.score().value).reversed()
                        .thenComparingInt(Scored::substitutions)
                        .thenComparing(entry -> entry.candidate().shortName(), alphabetical))
                .limit(TEACHER_LIMIT)
                .map(entry -> entry.score().toOption(ChangeKind.REPLACE_TEACHER,
                        "Замінити на " + entry.candidate().shortName(), entry.candidate().id(),
                        target.disciplineId(), null, null, null))
                .toList();
    }

    static List<RankedOption> replaceDiscipline(RankingInput input, Comparator<String> alphabetical) {
        Target target = input.target();
        List<RankedOption> options = new ArrayList<>();
        for (GroupDiscipline discipline : input.groupDisciplines()) {
            if (discipline.disciplineId().equals(target.disciplineId()) || discipline.finished()
                    || !available(discipline.teacherId(), input)) {
                continue;
            }
            Score score = new Score(DISCIPLINE_BASE);
            score.reason("Веде в цій групі " + discipline.shortName());
            if (discipline.hoursBehind() > 0) {
                score.add(BEHIND_PLAN, "Відстає на " + discipline.hoursBehind() + " год");
            }
            dayFactors(discipline.teacherId(), input, score);
            unwanted(discipline.teacherId(), input, score);
            if (discipline.requiredRoomType() != null && target.roomType() != null
                    && discipline.requiredRoomType() != target.roomType()) {
                score.warn("Потрібна аудиторія типу «" + DatedSlotChecker.roomTypeLabel(discipline.requiredRoomType())
                        + "»");
            }
            options.add(score.toOption(ChangeKind.REPLACE_DISCIPLINE,
                    "Замінити на " + discipline.shortName() + " (" + discipline.teacherShortName() + ")",
                    discipline.teacherId(), discipline.disciplineId(), null, null, null));
        }
        return options.stream()
                .sorted(Comparator.comparingInt(RankedOption::rawScore).reversed()
                        .thenComparing(RankedOption::title, alphabetical))
                .limit(DISCIPLINE_LIMIT)
                .toList();
    }

    static List<RankedOption> swap(RankingInput input) {
        Target target = input.target();
        int lastPair = cohortPairs(input).stream().mapToInt(Integer::intValue).max().orElse(target.pair());
        List<RankedOption> options = new ArrayList<>();
        for (GroupLesson later : input.groupDay()) {
            if (later.pair() <= target.pair() || later.subgroup() != target.subgroup() || later.decided()
                    || !available(later.teacherId(), input)) {
                continue;
            }
            Score score = new Score(SWAP_BASE);
            score.reason(later.disciplineShortName() + " (" + later.teacherShortName() + "): викладач вільний на "
                    + target.pair() + " парі");
            boolean last = later.pair() == lastPair;
            if (last) {
                score.add(SWAP_LAST_PAIR, "Група піде раніше");
            } else {
                score.add(-SWAP_LEAVES_WINDOW, null);
                score.warn("У групи з'явиться вікно на " + later.pair() + " парі");
            }
            unwanted(later.teacherId(), input, score);
            String title = "Переставити " + later.pair() + " пару на " + target.pair()
                    + (last ? ": група піде раніше" : "");
            options.add(score.toOption(ChangeKind.SWAP, title, later.teacherId(), later.disciplineId(),
                    later.lessonId(), null, null));
        }
        return options;
    }

    static RankedOption cancel(RankingInput input) {
        int pair = input.target().pair();
        List<Integer> others = cohortPairs(input);
        Score score = new Score(CANCEL_BASE);
        if (others.isEmpty()) {
            score.add(CANCEL_EDGE_PAIR, "Єдина пара групи цього дня");
        } else if (others.stream().allMatch(other -> other > pair)) {
            score.add(CANCEL_EDGE_PAIR, "Перша пара: група прийде пізніше");
        } else if (others.stream().allMatch(other -> other < pair)) {
            score.add(CANCEL_EDGE_PAIR, "Остання пара: група піде раніше");
        } else {
            score.add(-CANCEL_MIDDLE_PAIR, null);
            score.warn("У групи з'явиться вікно");
        }
        return score.toOption(ChangeKind.CANCEL, "Пари не буде", null, null, null, null, null);
    }

    static List<RankedOption> makeUp(RankingInput input) {
        Target target = input.target();
        if (target.teacherId() == null) {
            return List.of();
        }
        return input.makeUpSlots().stream()
                .map(slot -> makeUpOption(target, slot))
                .toList();
    }

    private static RankedOption makeUpOption(Target target, MakeUpSlot slot) {
        Score score = new Score(MAKE_UP_BASE);
        score.reason("Група й викладач вільні, вікна не буде");
        return score.toOption(ChangeKind.ADDITIONAL,
                "Відпрацювання: " + UkrainianText.dayMonth(slot.date()) + ", " + slot.pair() + " пара",
                target.teacherId(), target.disciplineId(), null, slot.date(), slot.pair());
    }

    /** Free teacher who may take the slot: not the absent one, not absent, not busy, not UNAVAILABLE. */
    static boolean available(UUID teacherId, RankingInput input) {
        return teacherId != null
                && !teacherId.equals(input.target().teacherId())
                && !input.absentTeacherIds().contains(teacherId)
                && !input.busyTeacherIds().contains(teacherId)
                && input.slotPreferences().get(teacherId) != PreferenceKind.UNAVAILABLE;
    }

    /** +15 closes a window, +10 already has lessons that day. */
    private static void dayFactors(UUID teacherId, RankingInput input, Score score) {
        List<Integer> pairs = input.teacherPairsThatDay().getOrDefault(teacherId, List.of());
        int pair = input.target().pair();
        OptionalInt before = pairs.stream().mapToInt(Integer::intValue).filter(other -> other < pair).max();
        OptionalInt after = pairs.stream().mapToInt(Integer::intValue).filter(other -> other > pair).min();
        if (before.isPresent() && after.isPresent()) {
            score.add(CLOSES_WINDOW, "Закриває вікно між " + before.getAsInt() + " і " + after.getAsInt() + " парою");
        }
        long count = pairs.stream().distinct().count();
        if (count > 0) {
            score.add(HAS_LESSONS_THAT_DAY,
                    "Вже має " + count + " " + UkrainianText.plural(count, "пару", "пари", "пар") + " цього дня");
        }
    }

    private static void unwanted(UUID teacherId, RankingInput input, Score score) {
        if (input.slotPreferences().get(teacherId) == PreferenceKind.UNWANTED) {
            score.add(-UNWANTED_SLOT, null);
            score.warn("Небажаний час (побажання викладача)");
        }
    }

    /** Pairs of the group that day that share students with the lesson's cohort. */
    private static List<Integer> cohortPairs(RankingInput input) {
        int subgroup = input.target().subgroup();
        return input.groupDay().stream()
                .filter(lesson -> PlannedLesson.cohortsOverlap(lesson.subgroup(), subgroup))
                .map(GroupLesson::pair)
                .filter(pair -> pair != input.target().pair())
                .toList();
    }

    /** Running score with its chips. */
    private static final class Score {

        private int value;
        private final List<String> reasons = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        Score(int base) {
            this.value = base;
        }

        void add(int points, String reason) {
            value += points;
            reason(reason);
        }

        void reason(String reason) {
            if (reason != null) {
                reasons.add(reason);
            }
        }

        void warn(String warning) {
            warnings.add(warning);
        }

        RankedOption toOption(ChangeKind kind, String title, UUID teacherId, UUID disciplineId, UUID swapLessonId,
                              LocalDate date, Integer pair) {
            return new RankedOption(kind, value, title, reasons, warnings, teacherId, disciplineId, swapLessonId,
                    date, pair);
        }
    }
}
