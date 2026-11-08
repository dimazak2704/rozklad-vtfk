package ua.vtc.rozklad.core.substitution.internal.support;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Which change created the original lesson of another change, among a set of confirmed and applied
 * changes (one lesson lookup). A later change may decide about a lesson an earlier change created
 * (an amendment cancels or re-assigns a substitute's pair): then the earlier substitution did not
 * happen. Room changes in between keep the substitute and are looked through. Used by the teacher
 * statistics and by the cover burden of the substitute ranking.
 */
public final class ChangeLineage {

    private static final Set<ChangeKind> TAKES_OVER = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.DISCIPLINE_FINISHED);

    private final Collection<Change> changes;
    private final Map<UUID, Change> byId;
    private final Map<UUID, UUID> creatorOfLesson;

    private ChangeLineage(Collection<Change> changes, Map<UUID, Change> byId, Map<UUID, UUID> creatorOfLesson) {
        this.changes = changes;
        this.byId = byId;
        this.creatorOfLesson = creatorOfLesson;
    }

    public static ChangeLineage of(Collection<Change> done, LessonRepository lessons) {
        Set<UUID> originals = done.stream().map(Change::getOriginalLessonId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> creators = originals.isEmpty() ? Map.of() : lessons.findAllById(originals).stream()
                .filter(lesson -> lesson.getChangeId() != null)
                .collect(Collectors.toMap(Lesson::getId, Lesson::getChangeId));
        return new ChangeLineage(done, done.stream().collect(Collectors.toMap(Change::getId, Function.identity())),
                creators);
    }

    /** The teacher takes a colleague's pair (same or another discipline). */
    public static boolean takesOver(Change change) {
        return TAKES_OVER.contains(change.getKind()) && change.getOrigTeacherId() != null
                && change.getNewTeacherId() != null && !change.getNewTeacherId().equals(change.getOrigTeacherId());
    }

    /** The nearest earlier change behind this one's lesson, looking through room changes; {@code null} for a timetable lesson. */
    public Change sourceOf(Change change) {
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

    /** Ids of substitutions that a later change of the set re-decided (the substitute's pair did not happen). */
    public Set<UUID> undoneSubstitutions() {
        Set<UUID> undone = new HashSet<>();
        for (Change change : changes) {
            Change source = sourceOf(change);
            if (source != null && change.getKind() != ChangeKind.ROOM_CHANGE && takesOver(source)
                    && Objects.equals(source.getNewTeacherId(), change.getOrigTeacherId())) {
                undone.add(source.getId());
            }
        }
        return undone;
    }

    private Change parentOf(Change change) {
        UUID creator = change.getOriginalLessonId() == null ? null : creatorOfLesson.get(change.getOriginalLessonId());
        return creator == null ? null : byId.get(creator);
    }
}
