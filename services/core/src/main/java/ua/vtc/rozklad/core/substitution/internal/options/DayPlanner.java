package ua.vtc.rozklad.core.substitution.internal.options;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Target;

/**
 * Proposal for every undecided lesson of an absence on one day (DOMAIN §7, «Запропонувати план
 * дня») — a pure, deterministic function, nothing is sent. Lessons with the fewest CLEAN options go
 * first (then by pair); each takes its option #1, and a teacher proposed so becomes busy at that
 * pair and gets the pair in his day. For a later lesson next to his proposed pair in the same group
 * and cohort he gets the bundle bonus (two pairs in a row are one stint); for any other later lesson
 * he counts one more cover pair and costs {@link #ANOTHER_PAIR} more. A lesson in the past gets no
 * proposal.
 */
public final class DayPlanner {

    /** Extra cost of a teacher already proposed for another pair of the same absence. */
    public static final int ANOTHER_PAIR = 6;

    private DayPlanner() {
    }

    /** One lesson of the plan: its options as ranked when its turn came, and the proposed one. */
    public record Planned(RankingInput input, List<RankedOption> options, Integer proposedIndex) {
    }

    private record Proposal(UUID groupId, int subgroup, int pair) {
    }

    /**
     * @param groupOf group of every target lesson (the input has no group id)
     * @return the lessons in the order they were planned
     */
    public static List<Planned> plan(List<RankingInput> inputs, Map<UUID, UUID> groupOf) {
        Map<UUID, Long> cleanOptions = new HashMap<>();
        inputs.forEach(input -> cleanOptions.put(input.target().lessonId(), OptionRanker.rank(input).stream()
                .filter(option -> option.band() == Band.CLEAN).count()));
        List<RankingInput> order = inputs.stream()
                .sorted(Comparator.comparingLong((RankingInput input) -> cleanOptions.get(input.target().lessonId()))
                        .thenComparingInt(input -> input.target().pair())
                        .thenComparing(input -> input.target().lessonId()))
                .toList();

        Map<Integer, Set<UUID>> busy = new HashMap<>();
        Map<UUID, Set<Integer>> pairs = new HashMap<>();
        Map<UUID, List<Proposal>> proposals = new HashMap<>();
        Set<UUID> decided = new HashSet<>();
        List<Planned> result = new ArrayList<>(order.size());
        for (RankingInput original : order) {
            Target target = original.target();
            RankingInput input = adjusted(original, busy, pairs, proposals, decided, groupOf.get(target.lessonId()));
            List<RankedOption> options = OptionRanker.rank(input);
            if (target.past() || options.isEmpty()) {
                result.add(new Planned(input, options, null));
                continue;
            }
            RankedOption chosen = options.getFirst();
            result.add(new Planned(input, options, 0));
            decided.add(target.lessonId());
            if (chosen.swapLessonId() != null) {
                decided.add(chosen.swapLessonId());
            }
            if (chosen.teacherId() != null && chosen.kind() != ChangeKind.CANCEL) {
                busy.computeIfAbsent(target.pair(), pair -> new HashSet<>()).add(chosen.teacherId());
                pairs.computeIfAbsent(chosen.teacherId(), teacher -> new TreeSet<>()).add(target.pair());
                if (chosen.kind() == ChangeKind.REPLACE_DISCIPLINE) {
                    proposals.computeIfAbsent(chosen.teacherId(), teacher -> new ArrayList<>())
                            .add(new Proposal(groupOf.get(target.lessonId()), target.subgroup(), target.pair()));
                }
            }
        }
        return result;
    }

    /** The input as it is after the earlier proposals of the plan. */
    private static RankingInput adjusted(RankingInput input, Map<Integer, Set<UUID>> busy,
                                         Map<UUID, Set<Integer>> pairs, Map<UUID, List<Proposal>> proposals,
                                         Set<UUID> decided, UUID groupId) {
        if (busy.isEmpty() && decided.isEmpty()) {
            return input;
        }
        Target target = input.target();
        Map<Integer, Set<UUID>> busyByPair = new HashMap<>();
        input.busyByPair().forEach((pair, teachers) -> busyByPair.put(pair, new HashSet<>(teachers)));
        busy.forEach((pair, teachers) -> busyByPair.computeIfAbsent(pair, key -> new HashSet<>()).addAll(teachers));
        Map<UUID, List<Integer>> teacherPairs = new HashMap<>(input.teacherPairsThatDay());
        pairs.forEach((teacher, extra) -> {
            Set<Integer> all = new TreeSet<>(teacherPairs.getOrDefault(teacher, List.of()));
            all.addAll(extra);
            teacherPairs.put(teacher, List.copyOf(all));
        });
        Map<UUID, Double> burden = new HashMap<>(input.coverBurden());
        Map<UUID, Integer> count = new HashMap<>(input.coverCount());
        Map<UUID, Integer> penalty = new HashMap<>(input.planPenalty());
        proposals.forEach((teacher, proposed) -> {
            boolean bundle = proposed.stream().anyMatch(other -> other.groupId().equals(groupId)
                    && other.subgroup() == target.subgroup() && Math.abs(other.pair() - target.pair()) == 1);
            if (bundle) {
                penalty.merge(teacher, -OptionRanker.BUNDLE, Integer::sum);
            } else {
                burden.merge(teacher, (double) proposed.size(), Double::sum);
                count.merge(teacher, proposed.size(), Integer::sum);
                penalty.merge(teacher, ANOTHER_PAIR, Integer::sum);
            }
        });
        List<GroupLesson> groupDay = input.groupDay().stream()
                .map(lesson -> decided.contains(lesson.lessonId()) && !lesson.decided()
                        ? new GroupLesson(lesson.lessonId(), lesson.pair(), lesson.subgroup(), lesson.disciplineId(),
                        lesson.disciplineShortName(), lesson.teacherId(), lesson.teacherShortName(), lesson.roomId(),
                        true)
                        : lesson)
                .toList();
        return new RankingInput(target, groupDay, input.groupDisciplines(), teacherPairs, busyByPair,
                input.absentByPair(), input.slotPreferences(), input.weeklyHours(), burden, count, input.lastCover(),
                input.declinedThisLesson(), input.declinedToday(), penalty);
    }
}
