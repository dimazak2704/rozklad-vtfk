package ua.vtc.rozklad.core.substitution.internal.options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.effect.DatedSlotChecker;
import ua.vtc.rozklad.core.substitution.internal.effect.PlannedLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Progress;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Target;

/**
 * Ranks the ways to handle a lesson whose teacher is absent (DOMAIN §7) — a pure, deterministic
 * function of {@link RankingInput}.
 *
 * <p>Kinds: REPLACE_DISCIPLINE (another discipline of the same group and cohort, by its own
 * teacher), MERGE_SUBGROUPS (the other subgroup's teacher of the same discipline takes both),
 * SWAP and CANCEL. REPLACE_TEACHER and make-up pairs are manual actions, never offered here.
 *
 * <p>Order: {@link Band} first, then kind (REPLACE_DISCIPLINE, MERGE_SUBGROUPS, SWAP, CANCEL), then
 * the integer cost (lower is better), then the teacher whose last cover is oldest, then the title.
 * Cost of a cover: load rank 0..10, cover burden 0..12, behind-plan priority 0..−10, closes a
 * window −4/−6, waiting between pairs 0..8, a run of four pairs +4, overrun +10 (⚠), unwanted slot
 * +8 (⚠), declined another pair today +6, room type +3 (⚠); a pair with this group already today
 * is a ⚠. Only #1 may be «recommended»: a CLEAN option clearly ahead of #2 for a lesson not in the past.
 */
public final class OptionRanker {

    public static final int LOAD_MAX = 10;
    public static final int BURDEN_MAX = 12;
    public static final int BEHIND_PLAN_MAX = 10;
    public static final int CLOSES_WINDOW_TIGHT = 6;
    public static final int CLOSES_WINDOW_LOOSE = 4;
    public static final int WAITING_MAX = 8;
    public static final int LONG_RUN = 4;
    public static final int LONG_RUN_PAIRS = 4;
    public static final int OVERRUN = 10;
    public static final int BUNDLE = 4;
    public static final int UNWANTED = 8;
    public static final int DECLINED_TODAY = 6;
    public static final int ROOM_TYPE = 3;
    /** #1 is recommended only when #2 is in a worse band or costs at least this much more. */
    public static final int RECOMMENDED_MARGIN = 5;

    private static final List<ChangeKind> KIND_ORDER = List.of(ChangeKind.REPLACE_DISCIPLINE,
            ChangeKind.MERGE_SUBGROUPS, ChangeKind.SWAP, ChangeKind.CANCEL);

    private OptionRanker() {
    }

    public static List<RankedOption> rank(RankingInput input) {
        return rank(input, EnumSet.noneOf(RankingFactor.class));
    }

    /** Ranking with some factors switched off — only for the offline evaluation (ablation). */
    static List<RankedOption> rank(RankingInput input, Set<RankingFactor> disabled) {
        Factors factors = new Factors(disabled);
        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(replaceDiscipline(input, factors));
        drafts.addAll(merge(input));
        drafts.addAll(swap(input, factors));
        drafts.add(cancel(input));
        drafts.forEach(Draft::resolveBand);
        Comparator<String> alphabetical = UkrainianText.collator();
        List<Draft> sorted = drafts.stream()
                .sorted(Comparator.comparing((Draft draft) -> draft.band)
                        .thenComparingInt(draft -> KIND_ORDER.indexOf(draft.kind))
                        .thenComparingInt(draft -> draft.cost)
                        .thenComparing(draft -> lastCover(draft, input), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(draft -> draft.title, alphabetical)
                        .thenComparing(draft -> Objects.toString(draft.teacherId, ""))
                        .thenComparing(draft -> Objects.toString(draft.swapLessonId, "")))
                .toList();
        boolean recommended = !input.target().past() && !sorted.isEmpty()
                && sorted.getFirst().band == Band.CLEAN
                && (sorted.size() == 1 || sorted.get(1).band.compareTo(Band.CLEAN) > 0
                || sorted.get(1).cost >= sorted.getFirst().cost + RECOMMENDED_MARGIN);
        List<RankedOption> ranked = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            ranked.add(sorted.get(index).toOption(index == 0 && recommended, Math.max(0, 100 - 10 * index)));
        }
        return ranked;
    }

    // ---------------------------------------------------------------- REPLACE_DISCIPLINE

    /** Another discipline of the group and cohort by its own teacher; one option per teacher (H8). */
    static List<Draft> replaceDiscipline(RankingInput input, Factors factors) {
        Target target = input.target();
        List<GroupDiscipline> pool = input.groupDisciplines().stream()
                .filter(item -> eligible(item, input))
                .distinct()
                .toList();
        if (pool.isEmpty()) {
            return List.of();
        }
        Map<UUID, Double> loadRank = averagedRank(pool.stream().map(GroupDiscipline::teacherId).distinct().toList(),
                teacher -> input.weeklyHours().getOrDefault(teacher, BigDecimal.ZERO).doubleValue());
        Map<GroupDiscipline, Double> lagRank = averagedRank(pool,
                item -> item.progress() == null ? 0 : item.progress().relativeLag());
        int teachers = loadRank.size();

        Map<UUID, List<Draft>> byTeacher = new LinkedHashMap<>();
        for (GroupDiscipline item : pool) {
            Draft draft = Draft.of(ChangeKind.REPLACE_DISCIPLINE, item.shortName() + " — " + item.teacherShortName(),
                    item.teacherId(), item.disciplineId(), null, item.ownRoomId());
            draft.progress = item.progress();
            draft.disciplineName = item.shortName();
            behindPlan(item, lagRank.get(item), draft, factors);
            dayShape(item.teacherId(), target.pair(), input, draft, factors, true);
            load(item.teacherId(), loadRank.get(item.teacherId()), teachers, input, draft, factors);
            bundle(item, input, draft, factors);
            burden(item.teacherId(), input, draft, factors);
            dayChip(item.teacherId(), input, draft);
            if (factors.on(RankingFactor.DECLINED_TODAY) && input.declinedToday().contains(item.teacherId())) {
                draft.add(DECLINED_TODAY, "Сьогодні вже відмовився(лась) від іншої пари");
            }
            int planned = input.planPenalty().getOrDefault(item.teacherId(), 0);
            if (planned != 0) {
                draft.add(planned, planned > 0 ? "У плані дня вже має іншу пару" : "У плані дня веде й сусідню пару");
            }
            overrun(item, input, draft, factors);
            unwanted(item.teacherId(), input, draft, factors);
            roomType(item, input, draft, factors);
            withGroupToday(item.teacherId(), input, draft, factors);
            byTeacher.computeIfAbsent(item.teacherId(), teacher -> new ArrayList<>()).add(draft);
        }
        Comparator<String> alphabetical = UkrainianText.collator();
        List<Draft> result = new ArrayList<>();
        for (List<Draft> options : byTeacher.values()) {
            List<Draft> ordered = options.stream()
                    .sorted(Comparator.comparingInt((Draft draft) -> draft.cost)
                            .thenComparing(Comparator.comparingInt((Draft draft) -> draft.progress == null ? 0
                                    : draft.progress.plan() - draft.progress.forecast()).reversed())
                            .thenComparing(draft -> draft.disciplineName, alphabetical)
                            .thenComparing(draft -> draft.disciplineId.toString()))
                    .toList();
            Draft best = ordered.getFirst();
            if (ordered.size() > 1) {
                best.reason("Також веде: " + ordered.subList(1, ordered.size()).stream()
                        .map(draft -> draft.disciplineName).collect(Collectors.joining(", ")));
            }
            result.add(best);
        }
        return result;
    }

    /** H2–H6: another discipline with hours left, a present, free teacher who has not declined. */
    static boolean eligible(GroupDiscipline item, RankingInput input) {
        Target target = input.target();
        if (item.teacherId() == null || item.disciplineId().equals(target.disciplineId())) {
            return false;
        }
        Progress progress = item.progress();
        if (progress != null && (progress.status() == AccountingStatus.FINISHED
                || progress.plan() - progress.fact() < target.hoursPerPair())) {
            return false;
        }
        return available(item.teacherId(), target.pair(), input);
    }

    /** H4–H6 at a pair: not the absent teacher, not absent, not busy, not UNAVAILABLE, not a decliner. */
    static boolean available(UUID teacherId, int pair, RankingInput input) {
        return teacherId != null
                && !teacherId.equals(input.target().teacherId())
                && !input.absent(teacherId, pair)
                && !input.busy(teacherId, pair)
                && (pair != input.target().pair() || input.slotPreferences().get(teacherId) != PreferenceKind.UNAVAILABLE)
                && !input.declinedThisLesson().contains(teacherId);
    }

    /** Priority of a discipline behind plan, by relative lag inside the pool: 0..−10. */
    private static void behindPlan(GroupDiscipline item, double rank, Draft draft, Factors factors) {
        if (!factors.on(RankingFactor.BEHIND_PLAN) || item.progress() == null || item.progress().relativeLag() <= 0) {
            return;
        }
        int bonus = (int) Math.round(BEHIND_PLAN_MAX * rank);
        if (bonus > 0) {
            draft.add(-bonus, item.shortName() + " відстає від плану");
        }
    }

    /** Ascending rank of teaching load inside the pool, 0..10; a chip for the least loaded of three or more. */
    private static void load(UUID teacherId, double rank, int teachers, RankingInput input, Draft draft,
                             Factors factors) {
        if (!factors.on(RankingFactor.LOAD)) {
            return;
        }
        int cost = (int) Math.round(LOAD_MAX * rank);
        BigDecimal hours = input.weeklyHours().getOrDefault(teacherId, BigDecimal.ZERO);
        draft.add(cost, rank == 0 && teachers >= 3
                ? "Менше навантаження серед викладачів групи (" + hours.stripTrailingZeros().toPlainString()
                + " год/тиж)" : null);
    }

    /** Recent cover pairs normalised by teaching load: min(12, round(3 · B · 18 / max(6, w))). */
    private static void burden(UUID teacherId, RankingInput input, Draft draft, Factors factors) {
        double burden = input.coverBurden().getOrDefault(teacherId, 0.0);
        if (burden <= 0) {
            return;
        }
        double hours = input.weeklyHours().getOrDefault(teacherId, BigDecimal.ZERO).doubleValue();
        double normalised = burden * 18 / Math.max(6, hours);
        int cost = factors.on(RankingFactor.COVER_BURDEN) ? Math.min(BURDEN_MAX, (int) Math.round(3 * normalised)) : 0;
        int count = input.coverCount().getOrDefault(teacherId, 0);
        draft.add(cost, count + " " + UkrainianText.plural(count, "заміна", "заміни", "замін") + " за 6 тижнів");
    }

    /** −4 when the teacher could take the absent teacher's adjacent undecided pair of the cohort as well. */
    private static void bundle(GroupDiscipline item, RankingInput input, Draft draft, Factors factors) {
        Target target = input.target();
        if (!factors.on(RankingFactor.BUNDLE) || target.teacherId() == null) {
            return;
        }
        input.groupDay().stream()
                .filter(lesson -> !lesson.decided() && target.teacherId().equals(lesson.teacherId())
                        && lesson.subgroup() == target.subgroup() && Math.abs(lesson.pair() - target.pair()) == 1
                        && !lesson.disciplineId().equals(item.disciplineId())
                        && available(item.teacherId(), lesson.pair(), input))
                .mapToInt(GroupLesson::pair)
                .min()
                .ifPresent(pair -> draft.add(-BUNDLE, "Може взяти й " + pair + " пару"));
    }

    private static void overrun(GroupDiscipline item, RankingInput input, Draft draft, Factors factors) {
        Progress progress = item.progress();
        if (!factors.on(RankingFactor.OVERRUN) || progress == null) {
            return;
        }
        if (progress.forecast() + input.target().hoursPerPair() > progress.plan()
                || progress.status() == AccountingStatus.WILL_OVERRUN) {
            draft.add(OVERRUN, null);
            draft.warn(item.shortName() + " піде понад план");
        }
    }

    private static void roomType(GroupDiscipline item, RankingInput input, Draft draft, Factors factors) {
        Target target = input.target();
        if (!factors.on(RankingFactor.ROOM_TYPE) || item.ownRoomId() != null || target.online()
                || item.requiredRoomType() == null || target.roomType() == null
                || item.requiredRoomType() == target.roomType()) {
            return;
        }
        draft.add(ROOM_TYPE, null);
        draft.warn("Потрібна аудиторія «" + DatedSlotChecker.roomTypeLabel(item.requiredRoomType()) + "»");
    }

    /** A teacher who has a pair with this group today would rather swap it (⚠, no cost). */
    private static void withGroupToday(UUID teacherId, RankingInput input, Draft draft, Factors factors) {
        if (!factors.on(RankingFactor.WITH_GROUP_TODAY)) {
            return;
        }
        int subgroup = input.target().subgroup();
        input.groupDay().stream()
                .filter(lesson -> teacherId.equals(lesson.teacherId())
                        && PlannedLesson.cohortsOverlap(lesson.subgroup(), subgroup))
                .mapToInt(GroupLesson::pair)
                .min()
                .ifPresent(pair -> draft.warn("Вже має пару з цією групою (" + pair + " пара) — краще перестановка"));
    }

    // ---------------------------------------------------------------- day shape (RD and SWAP)

    /**
     * The teacher's day with the new pair: closing a window is convenient (−6 when both neighbours
     * are own pairs, −4 otherwise), waiting {@code g ≥ 2} empty pairs costs 2·(g−1) up to 8 (none for
     * an online lesson), a run of four or more pairs +4. Pairs 1–8 are all normal: no daily cap.
     */
    private static void dayShape(UUID teacherId, int pair, RankingInput input, Draft draft, Factors factors,
                                 boolean closingBonus) {
        List<Integer> own = input.teacherPairsThatDay().getOrDefault(teacherId, List.of()).stream()
                .filter(other -> other != pair).toList();
        OptionalInt before = own.stream().mapToInt(Integer::intValue).filter(other -> other < pair).max();
        OptionalInt after = own.stream().mapToInt(Integer::intValue).filter(other -> other > pair).min();
        if (closingBonus && factors.on(RankingFactor.CLOSES_WINDOW) && before.isPresent() && after.isPresent()) {
            boolean tight = before.getAsInt() == pair - 1 && after.getAsInt() == pair + 1;
            draft.add(-(tight ? CLOSES_WINDOW_TIGHT : CLOSES_WINDOW_LOOSE), tight
                    ? "Закриває вікно в розкладі викладача" : "Зменшує вікно в розкладі викладача");
        }
        if (!own.isEmpty() && factors.on(RankingFactor.WAITING) && !input.target().online()) {
            int distance = own.stream().mapToInt(other -> Math.abs(other - pair)).min().orElseThrow();
            int waiting = distance - 1;
            if (waiting >= 2) {
                draft.add(Math.min(WAITING_MAX, 2 * (waiting - 1)), "Чекатиме " + waiting + " "
                        + UkrainianText.plural(waiting, "пару", "пари", "пар") + " між заняттями");
            }
        }
        if (factors.on(RankingFactor.LONG_RUN)) {
            int run = runThrough(own, pair);
            if (run >= LONG_RUN_PAIRS) {
                draft.add(LONG_RUN, run + " " + UkrainianText.plural(run, "пара", "пари", "пар") + " поспіль");
            }
        }
    }

    /** Info chip about the teacher's day: none, the pairs, or «Буде 5-та пара за день». */
    private static void dayChip(UUID teacherId, RankingInput input, Draft draft) {
        int pair = input.target().pair();
        List<Integer> own = input.teacherPairsThatDay().getOrDefault(teacherId, List.of()).stream()
                .filter(other -> other != pair).sorted().toList();
        if (own.isEmpty()) {
            draft.reason("Сьогодні пар немає");
        } else if (own.size() + 1 >= 5) {
            draft.reason("Буде " + ordinal(own.size() + 1) + " пара за день");
        } else {
            draft.reason("Сьогодні має " + own.stream().map(String::valueOf).collect(Collectors.joining(", "))
                    + (own.size() == 1 ? " пару" : " пари"));
        }
    }

    private static int runThrough(List<Integer> own, int pair) {
        Set<Integer> pairs = new TreeSet<>(own);
        pairs.add(pair);
        int run = 1;
        for (int next = pair + 1; pairs.contains(next); next++) {
            run++;
        }
        for (int previous = pair - 1; pairs.contains(previous); previous--) {
            run++;
        }
        return run;
    }

    /** «5-та», «7-ма». */
    static String ordinal(int number) {
        return number + (number == 7 || number == 8 ? "-ма" : number == 3 ? "-тя" : "-та");
    }

    private static void unwanted(UUID teacherId, RankingInput input, Draft draft, Factors factors) {
        if (factors.on(RankingFactor.UNWANTED) && input.slotPreferences().get(teacherId) == PreferenceKind.UNWANTED) {
            draft.add(UNWANTED, null);
            draft.warn("Небажаний час (побажання викладача)");
        }
    }

    // ---------------------------------------------------------------- MERGE_SUBGROUPS

    /**
     * The absent teacher teaches subgroup k and the same discipline runs for another subgroup at the
     * same pair with a present teacher (and no third cohort): that teacher takes both subgroups.
     */
    static List<Draft> merge(RankingInput input) {
        Target target = input.target();
        if (target.subgroup() == 0) {
            return List.of();
        }
        List<GroupLesson> atPair = input.groupDay().stream().filter(lesson -> lesson.pair() == target.pair()).toList();
        List<Draft> result = new ArrayList<>();
        for (GroupLesson partner : atPair) {
            boolean sameDiscipline = partner.disciplineId().equals(target.disciplineId());
            boolean otherSubgroup = partner.subgroup() > 0 && partner.subgroup() != target.subgroup();
            boolean onlyTheseTwo = atPair.stream().allMatch(other -> other.lessonId().equals(partner.lessonId()));
            if (!sameDiscipline || !otherSubgroup || !onlyTheseTwo || partner.decided() || partner.teacherId() == null
                    || partner.teacherId().equals(target.teacherId())
                    || input.absent(partner.teacherId(), target.pair())
                    || input.declinedThisLesson().contains(partner.teacherId())) {
                continue;
            }
            Draft draft = Draft.of(ChangeKind.MERGE_SUBGROUPS, "Об'єднати підгрупи: " + target.disciplineShortName()
                    + " — " + partner.teacherShortName(), partner.teacherId(), target.disciplineId(),
                    partner.lessonId(), partner.roomId());
            draft.reason(partner.teacherShortName() + " уже веде " + target.disciplineShortName() + " для "
                    + partner.subgroup() + " підгрупи — візьме обидві");
            result.add(draft);
        }
        return result;
    }

    // ---------------------------------------------------------------- SWAP

    /**
     * Another undecided lesson of exactly the same cohort moves into the slot: a later one, or for a
     * future day an earlier one. Cost: the moved teacher's day shape and an unwanted slot.
     */
    static List<Draft> swap(RankingInput input, Factors factors) {
        Target target = input.target();
        boolean futureDay = target.date().isAfter(target.today());
        List<Integer> cohort = cohortPairs(input);
        int last = cohort.stream().mapToInt(Integer::intValue).max().orElse(target.pair());
        int first = cohort.stream().mapToInt(Integer::intValue).min().orElse(target.pair());
        List<Draft> result = new ArrayList<>();
        for (GroupLesson moved : input.groupDay()) {
            boolean later = moved.pair() > target.pair();
            boolean earlier = moved.pair() < target.pair() && futureDay;
            if ((!later && !earlier) || moved.subgroup() != target.subgroup() || moved.decided()
                    || !available(moved.teacherId(), target.pair(), input)) {
                continue;
            }
            Draft draft = Draft.of(ChangeKind.SWAP, "Переставити " + moved.pair() + " пару ("
                    + moved.disciplineShortName() + ") на " + target.pair(), moved.teacherId(),
                    moved.disciplineId(), moved.lessonId(), null);
            if (later && moved.pair() == last) {
                draft.reason("Група піде раніше");
            } else if (earlier && moved.pair() == first) {
                draft.reason("Група прийде пізніше");
            } else {
                draft.warn("У групи з'явиться вікно на " + moved.pair() + " парі");
            }
            Draft shape = new Draft(ChangeKind.SWAP, "", null, null, null, null);
            dayShape(moved.teacherId(), target.pair(), withoutPair(input, moved), shape, factors, false);
            draft.cost += shape.cost;
            draft.reasons.addAll(shape.reasons);
            unwanted(moved.teacherId(), input, draft, factors);
            result.add(draft);
        }
        return result;
    }

    /** The input with the moved lesson's pair taken out of its teacher's day. */
    private static RankingInput withoutPair(RankingInput input, GroupLesson moved) {
        Map<UUID, List<Integer>> pairs = new HashMap<>(input.teacherPairsThatDay());
        pairs.computeIfPresent(moved.teacherId(), (teacher, own) -> own.stream()
                .filter(pair -> pair != moved.pair()).toList());
        return new RankingInput(input.target(), input.groupDay(), input.groupDisciplines(), pairs, input.busyByPair(),
                input.absentByPair(), input.slotPreferences(), input.weeklyHours(), input.coverBurden(),
                input.coverCount(), input.lastCover(), input.declinedThisLesson(), input.declinedToday(),
                input.planPenalty());
    }

    // ---------------------------------------------------------------- CANCEL

    /**
     * One cancel. EASY_CANCEL for the cohort's first, last or only pair when the lesson is online,
     * the group has a block, or the discipline's hours suffice; LAST otherwise (a middle pair leaves
     * a window).
     */
    static Draft cancel(RankingInput input) {
        Target target = input.target();
        int pair = target.pair();
        List<Integer> others = cohortPairs(input);
        Draft draft = Draft.of(ChangeKind.CANCEL, "Пари не буде", null, null, null, null);
        boolean edge = true;
        if (others.isEmpty()) {
            draft.reason("Єдина пара групи цього дня");
        } else if (others.stream().allMatch(other -> other > pair)) {
            draft.reason("Перша пара: група прийде пізніше");
        } else if (others.stream().allMatch(other -> other < pair)) {
            draft.reason("Остання пара: група піде раніше");
        } else {
            edge = false;
            draft.warn("У групи з'явиться вікно");
        }
        boolean easy = false;
        if (target.online()) {
            draft.reason("Заняття онлайн");
            easy = true;
        }
        if (target.groupHasBlock()) {
            draft.reason("У групи сьогодні блок");
            easy = true;
        }
        if (target.progress() != null && target.progress().hoursBehind() < target.hoursPerPair()) {
            draft.reason("Годин з " + target.disciplineShortName() + " вистачає");
            easy = true;
        }
        draft.band = edge && easy ? Band.EASY_CANCEL : Band.LAST;
        return draft;
    }

    /** Other pairs of the group that day that share students with the lesson's cohort. */
    private static List<Integer> cohortPairs(RankingInput input) {
        int subgroup = input.target().subgroup();
        return input.groupDay().stream()
                .filter(lesson -> PlannedLesson.cohortsOverlap(lesson.subgroup(), subgroup))
                .map(GroupLesson::pair)
                .filter(pair -> pair != input.target().pair())
                .distinct()
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private static LocalDate lastCover(Draft draft, RankingInput input) {
        return draft.teacherId == null ? null : input.lastCover().get(draft.teacherId);
    }

    /** Ascending rank of each key by its value, ties averaged, scaled to 0..1 (rank / (n − 1)); one key gets 0. */
    static <K> Map<K, Double> averagedRank(List<K> keys, ToDoubleFunction<K> value) {
        Map<K, Double> result = new HashMap<>();
        if (keys.size() == 1) {
            result.put(keys.getFirst(), 0.0);
            return result;
        }
        List<K> sorted = keys.stream().sorted(Comparator.comparingDouble(value)).toList();
        int index = 0;
        while (index < sorted.size()) {
            double current = value.applyAsDouble(sorted.get(index));
            int end = index;
            while (end + 1 < sorted.size() && value.applyAsDouble(sorted.get(end + 1)) == current) {
                end++;
            }
            double rank = (index + end) / 2.0 / (sorted.size() - 1);
            for (int tied = index; tied <= end; tied++) {
                result.put(sorted.get(tied), rank);
            }
            index = end + 1;
        }
        return result;
    }

    /** Which factors count (all of them outside the offline evaluation). */
    record Factors(Set<RankingFactor> disabled) {

        boolean on(RankingFactor factor) {
            return !disabled.contains(factor);
        }
    }

    /** An option while it is being costed. */
    static final class Draft {

        private final ChangeKind kind;
        private final String title;
        private final UUID teacherId;
        private final UUID disciplineId;
        private final UUID swapLessonId;
        private final UUID roomId;
        private final List<String> reasons = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private Band band;
        private int cost;
        private Progress progress;
        private String disciplineName = "";

        private Draft(ChangeKind kind, String title, UUID teacherId, UUID disciplineId, UUID swapLessonId,
                      UUID roomId) {
            this.kind = kind;
            this.title = title;
            this.teacherId = teacherId;
            this.disciplineId = disciplineId;
            this.swapLessonId = swapLessonId;
            this.roomId = roomId;
        }

        static Draft of(ChangeKind kind, String title, UUID teacherId, UUID disciplineId, UUID swapLessonId,
                        UUID roomId) {
            return new Draft(kind, title, teacherId, disciplineId, swapLessonId, roomId);
        }

        void add(int points, String reason) {
            cost += points;
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

        /** A cover or swap is CLEAN without a warning and CAUTION with one; a cancel sets its band itself. */
        void resolveBand() {
            if (band == null) {
                band = warnings.isEmpty() ? Band.CLEAN : Band.CAUTION;
            }
        }

        RankedOption toOption(boolean recommended, int score) {
            return new RankedOption(kind, band, cost, title, reasons, warnings, teacherId, disciplineId,
                    swapLessonId, roomId, recommended, score);
        }
    }
}
