package ua.vtc.rozklad.core.accounting.internal;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.accounting.internal.LedgerResult.Suggestion;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * Plan / fact / forecast of one discipline of a group (DOMAIN §9) as a pure function:
 * {@code fact} = ended lessons × hours per pair, {@code remaining} = lessons still ahead × hours
 * per pair, {@code forecast} = fact + remaining. The plan may be missed by one pair (2 hours)
 * without a warning.
 *
 * <p>A split discipline is counted per cohort: subgroup k attends its own lessons and the lessons
 * of the whole group, and the row reports the cohort with the largest forecast (the one that
 * finishes first and decides «Вичит.»).
 */
public final class HoursLedger {

    static final int TOLERANCE_HOURS = 2;

    private static final Comparator<LedgerLesson> CHRONOLOGICAL =
            Comparator.comparing(LedgerLesson::date).thenComparingInt(LedgerLesson::pair);

    private HoursLedger() {
    }

    public static LedgerResult compute(int plan, int hoursPerPair, List<LedgerLesson> lessons) {
        if (plan < 0) {
            throw new IllegalArgumentException("plan must not be negative");
        }
        if (hoursPerPair < 1) {
            throw new IllegalArgumentException("hoursPerPair must be positive");
        }
        List<LedgerLesson> cohort = representativeCohort(Objects.requireNonNull(lessons, "lessons"));
        int endedLessons = (int) cohort.stream().filter(LedgerLesson::ended).count();
        int fact = endedLessons * hoursPerPair;
        int remaining = (cohort.size() - endedLessons) * hoursPerPair;
        int forecast = fact + remaining;
        AccountingStatus status = status(plan, fact, forecast);

        int finishingIndex = finishingLessonIndex(plan, hoursPerPair, cohort);
        LocalDate finishDate = finishingIndex >= 0 ? cohort.get(finishingIndex).date() : null;
        Suggestion suggestion = suggestion(status, plan, forecast, hoursPerPair, finishDate,
                beyondPlan(plan, finishingIndex, cohort));
        return new LedgerResult(plan, fact, remaining, forecast, status, finishDate, suggestion);
    }

    static AccountingStatus status(int plan, int fact, int forecast) {
        if (fact >= plan) {
            return AccountingStatus.FINISHED;
        }
        if (forecast > plan + TOLERANCE_HOURS) {
            return AccountingStatus.WILL_OVERRUN;
        }
        if (forecast < plan - TOLERANCE_HOURS) {
            return AccountingStatus.WILL_UNDERRUN;
        }
        return AccountingStatus.ON_TRACK;
    }

    /** Lessons of the cohort with the largest forecast, in chronological order. */
    static List<LedgerLesson> representativeCohort(List<LedgerLesson> lessons) {
        var subgroups = new TreeSet<Integer>();
        lessons.stream().map(LedgerLesson::subgroup).filter(subgroup -> subgroup > 0).forEach(subgroups::add);
        if (subgroups.isEmpty()) {
            return lessons.stream().sorted(CHRONOLOGICAL).toList();
        }
        List<LedgerLesson> best = List.of();
        long bestEnded = -1;
        for (int subgroup : subgroups) {
            List<LedgerLesson> cohort = lessons.stream()
                    .filter(lesson -> lesson.subgroup() == 0 || lesson.subgroup() == subgroup)
                    .sorted(CHRONOLOGICAL)
                    .toList();
            long ended = cohort.stream().filter(LedgerLesson::ended).count();
            if (cohort.size() > best.size() || (cohort.size() == best.size() && ended > bestEnded)) {
                best = cohort;
                bestEnded = ended;
            }
        }
        return best;
    }

    /** Index of the lesson on which the cumulative hours reach the plan, or -1 (never, or plan 0). */
    private static int finishingLessonIndex(int plan, int hoursPerPair, List<LedgerLesson> cohort) {
        if (plan == 0) {
            return -1;
        }
        int cumulative = 0;
        for (int index = 0; index < cohort.size(); index++) {
            cumulative += hoursPerPair;
            if (cumulative >= plan) {
                return index;
            }
        }
        return -1;
    }

    /** Future lessons after the plan is reached (all future lessons when the plan is zero). */
    private static List<LedgerLesson> beyondPlan(int plan, int finishingIndex, List<LedgerLesson> cohort) {
        int firstBeyond;
        if (plan == 0) {
            firstBeyond = 0;
        } else if (finishingIndex >= 0) {
            firstBeyond = finishingIndex + 1;
        } else {
            return List.of();
        }
        return cohort.subList(firstBeyond, cohort.size()).stream().filter(lesson -> !lesson.ended()).toList();
    }

    private static Suggestion suggestion(AccountingStatus status, int plan, int forecast, int hoursPerPair,
                                         LocalDate finishDate, List<LedgerLesson> beyondPlan) {
        boolean overrun = status == AccountingStatus.FINISHED || status == AccountingStatus.WILL_OVERRUN;
        if (overrun && !beyondPlan.isEmpty()) {
            int count = beyondPlan.size();
            String pairs = count + " " + UkrainianText.plural(count, "пару", "пари", "пар");
            String text = finishDate == null
                    ? "Години не заплановані: зняти " + pairs
                    : "Вичитано " + UkrainianText.dayMonth(finishDate) + ": зняти " + pairs + " після цієї дати";
            List<UUID> ids = beyondPlan.stream().map(LedgerLesson::lessonId).toList();
            return new Suggestion(Suggestion.Kind.DISCIPLINE_FINISHED, text, beyondPlan.getFirst().date(), count, ids);
        }
        if (status == AccountingStatus.WILL_UNDERRUN) {
            int missingHours = plan - forecast;
            int count = Math.ceilDiv(missingHours, hoursPerPair);
            String text = "Не вистачає " + missingHours + " год: додати " + count + " "
                    + UkrainianText.plural(count, "пару", "пари", "пар");
            return new Suggestion(Suggestion.Kind.ADDITIONAL, text, null, count, List.of());
        }
        return null;
    }
}
