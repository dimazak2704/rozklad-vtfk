package ua.vtc.rozklad.core.substitution.internal.absence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.effect.PlannedLesson;
import ua.vtc.rozklad.core.timetable.Lesson;

/**
 * The current decision about each lesson: the not cancelled change made for it, or a block that
 * covers its pair. A live decision (open or applied) wins over declined ones whatever their age: a
 * declined change asked again is the decision even when a newer change was declined meanwhile.
 * Only a lesson whose changes are all declined needs a decision again (the newest decline is shown).
 */
@Component
public class LessonDecisions {

    private static final EnumSet<ChangeStatus> LIVE = EnumSet.complementOf(EnumSet.of(ChangeStatus.CANCELLED));

    private final ChangeRepository changes;

    public LessonDecisions(ChangeRepository changes) {
        this.changes = changes;
    }

    public Map<UUID, Change> of(Collection<Lesson> lessons) {
        if (lessons.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Change> decisions = new HashMap<>();
        changes.findAllByOriginalLessonIdIn(lessons.stream().map(Lesson::getId).toList()).stream()
                .filter(change -> LIVE.contains(change.getStatus()))
                .forEach(change -> decisions.merge(change.getOriginalLessonId(), change, LessonDecisions::current));

        LocalDate from = lessons.stream().map(Lesson::getDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = lessons.stream().map(Lesson::getDate).max(Comparator.naturalOrder()).orElseThrow();
        List<Change> blocks = changes.findAllByDateBetweenAndStatusIn(from, to, LIVE).stream()
                .filter(change -> change.getKind() == ChangeKind.BLOCK)
                .toList();
        for (Lesson lesson : lessons) {
            if (!decisions.containsKey(lesson.getId())) {
                blockCovering(lesson, blocks).ifPresent(block -> decisions.put(lesson.getId(), block));
            }
        }
        return decisions;
    }

    /** {@code true} when nobody has decided about the lesson yet, or the teacher declined. */
    public static boolean needsDecision(Change decision) {
        return decision == null || decision.getStatus() == ChangeStatus.DECLINED;
    }

    private static Optional<Change> blockCovering(Lesson lesson, List<Change> blocks) {
        return blocks.stream()
                .filter(block -> block.getDate().equals(lesson.getDate())
                        && block.getGroupId().equals(lesson.getGroupId())
                        && block.coversPair(lesson.getPairNumber())
                        && PlannedLesson.cohortsOverlap(block.getSubgroupNumber(), lesson.getSubgroupNumber()))
                .max(Comparator.comparing(Change::getCreatedAt));
    }

    /** The live one of two decisions about a lesson, else the newer one. */
    private static Change current(Change first, Change second) {
        boolean firstDeclined = first.getStatus() == ChangeStatus.DECLINED;
        if (firstDeclined != (second.getStatus() == ChangeStatus.DECLINED)) {
            return firstDeclined ? second : first;
        }
        return first.getCreatedAt().isAfter(second.getCreatedAt()) ? first : second;
    }
}
