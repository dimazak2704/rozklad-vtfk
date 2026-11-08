package ua.vtc.rozklad.core.substitution.internal.stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.accounting.TeacherActivityProvider;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.support.ReplacedLessons;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Teacher statistics from confirmed and applied changes (DOMAIN §9). A substitution is a change in
 * which the teacher takes a colleague's pair (same or another discipline); a missed pair is an own
 * pair that was given to someone else, moved away or cancelled, also by a block of pairs (each own
 * lesson the block replaces is one missed pair). Moving one's own lesson (SWAP), extra pairs and
 * blocks are not substitutions; «Вичит.» without a replacement is not a miss.
 * <p>
 * A later change may decide about a lesson an earlier change created (an amendment cancels or
 * re-assigns a substitute's pair). Then the earlier substitution did not happen, and the later
 * change takes no own pair from the substitute (the pair belongs to the absent teacher, who is
 * already counted once). A room change in between keeps the substitute and is looked through.
 */
@Service
@Transactional(readOnly = true)
class SubstitutionTeacherActivity implements TeacherActivityProvider {

    private static final Set<ChangeStatus> DONE = EnumSet.of(ChangeStatus.CONFIRMED, ChangeStatus.APPLIED);
    private static final Set<ChangeKind> TAKES_OVER = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.DISCIPLINE_FINISHED);
    private static final Set<ChangeKind> LOSES_PAIR = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.CANCEL, ChangeKind.SWAP);
    private static final Set<ChangeKind> EXTRA_PAIR = EnumSet.of(ChangeKind.ADDITIONAL, ChangeKind.BLOCK);

    private final ChangeRepository changes;
    private final LessonRepository lessons;
    private final ReplacedLessons replacedLessons;

    SubstitutionTeacherActivity(ChangeRepository changes, LessonRepository lessons, ReplacedLessons replacedLessons) {
        this.changes = changes;
        this.lessons = lessons;
        this.replacedLessons = replacedLessons;
    }

    @Override
    public List<TeacherActivity> activityBetween(LocalDate from, LocalDate to) {
        List<Change> done = changes.findAllByDateBetweenAndStatusIn(from, to, DONE);
        Lineage lineage = Lineage.of(done, lessons);
        Set<UUID> undone = new HashSet<>();
        Set<UUID> notOwnPair = new HashSet<>();
        for (Change change : done) {
            Change source = lineage.sourceOf(change);
            if (source == null) {
                continue;
            }
            boolean substitute = takesOver(source) && Objects.equals(source.getNewTeacherId(), change.getOrigTeacherId());
            if (substitute && change.getKind() != ChangeKind.ROOM_CHANGE) {
                undone.add(source.getId());
            }
            if (substitute || EXTRA_PAIR.contains(source.getKind())) {
                notOwnPair.add(change.getId());
            }
        }

        Map<UUID, Counter> counters = new LinkedHashMap<>();
        for (Change change : done) {
            UUID original = change.getOrigTeacherId();
            UUID assigned = change.getNewTeacherId();
            boolean otherTeacher = assigned != null && !assigned.equals(original);
            if (takesOver(change) && !undone.contains(change.getId())) {
                Counter counter = counters.computeIfAbsent(assigned, id -> new Counter());
                counter.substitutions++;
                counter.substitutedPairs += change.pairCount();
            }
            if (LOSES_PAIR.contains(change.getKind()) && original != null && (assigned == null || otherTeacher)
                    && !notOwnPair.contains(change.getId())) {
                counters.computeIfAbsent(original, id -> new Counter()).missedPairs += change.pairCount();
            }
        }
        for (Lesson lesson : ownLessonsTakenByBlocks(done)) {
            counters.computeIfAbsent(lesson.getTeacherId(), id -> new Counter()).missedPairs++;
        }
        return counters.entrySet().stream()
                .map(entry -> new TeacherActivity(entry.getKey(), entry.getValue().substitutions,
                        entry.getValue().substitutedPairs, entry.getValue().missedPairs))
                .toList();
    }

    /**
     * Timetable lessons (not created by another change) that blocks replace, taught by someone else
     * than the block's teacher: the lessons a published block cancelled, or for a confirmed block the
     * lessons of its cohort in its pairs that it will cancel. A lesson another change decides about is
     * counted with that change.
     */
    private List<Lesson> ownLessonsTakenByBlocks(List<Change> done) {
        List<Change> blocks = done.stream().filter(change -> change.getKind() == ChangeKind.BLOCK).toList();
        if (blocks.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> applied = replacedLessons.of(blocks.stream()
                .filter(block -> block.getStatus() == ChangeStatus.APPLIED).map(Change::getId).toList());
        // пару, про яку є окреме рішення (скасування в тому ж розпорядженні), уже враховано
        Set<UUID> decidedElsewhere = done.stream().filter(change -> change.getKind() != ChangeKind.BLOCK)
                .flatMap(change -> Stream.of(change.getOriginalLessonId(), change.getSwapLessonId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Lesson> taken = new ArrayList<>();
        for (Change block : blocks) {
            List<Lesson> replaced = block.getStatus() == ChangeStatus.APPLIED
                    ? lessons.findAllById(applied.getOrDefault(block.getId(), List.of()))
                    : lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(block.getGroupId(),
                                    block.getDate(), block.getDate()).stream()
                            .filter(Lesson::isScheduled)
                            .filter(lesson -> block.coversPair(lesson.getPairNumber()))
                            .filter(lesson -> block.getSubgroupNumber() == 0 || lesson.getSubgroupNumber() == 0
                                    || block.getSubgroupNumber() == lesson.getSubgroupNumber())
                            .toList();
            replaced.stream()
                    .filter(lesson -> lesson.getChangeId() == null && lesson.getTeacherId() != null)
                    .filter(lesson -> !decidedElsewhere.contains(lesson.getId()))
                    .filter(lesson -> !lesson.getTeacherId().equals(block.getNewTeacherId()))
                    .forEach(taken::add);
        }
        return taken;
    }

    /** The teacher takes a colleague's pair. */
    private static boolean takesOver(Change change) {
        return TAKES_OVER.contains(change.getKind()) && change.getOrigTeacherId() != null
                && change.getNewTeacherId() != null && !change.getNewTeacherId().equals(change.getOrigTeacherId());
    }

    /** Which change created the original lesson of a change (all of them are on the same date). */
    private record Lineage(Map<UUID, Change> byId, Map<UUID, UUID> creatorOfLesson) {

        static Lineage of(List<Change> done, LessonRepository lessons) {
            Set<UUID> originals = done.stream().map(Change::getOriginalLessonId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<UUID, UUID> creators = originals.isEmpty() ? Map.of() : lessons.findAllById(originals).stream()
                    .filter(lesson -> lesson.getChangeId() != null)
                    .collect(Collectors.toMap(Lesson::getId, Lesson::getChangeId));
            return new Lineage(done.stream().collect(Collectors.toMap(Change::getId, Function.identity())), creators);
        }

        /** The nearest earlier change behind this one's lesson, looking through room changes; {@code null} for a timetable lesson. */
        Change sourceOf(Change change) {
            Change source = parentOf(change);
            Set<UUID> seen = new HashSet<>();
            while (source != null && source.getKind() == ChangeKind.ROOM_CHANGE && seen.add(source.getId())) {
                Change further = parentOf(source);
                if (further == null) {
                    break;
                }
                source = further;
            }
            return source;
        }

        private Change parentOf(Change change) {
            UUID creator = change.getOriginalLessonId() == null ? null : creatorOfLesson.get(change.getOriginalLessonId());
            return creator == null ? null : byId.get(creator);
        }
    }

    private static final class Counter {
        private int substitutions;
        private int substitutedPairs;
        private int missedPairs;
    }
}
