package ua.vtc.rozklad.core.timetable.internal.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

/**
 * Difference between two weekly templates by (group, subgroup, weekday, pair, parity, discipline,
 * teacher, room). A lesson that disappeared and reappeared for the same group, subgroup, discipline
 * and teacher at another slot or room is reported as moved (nearest slot first).
 */
@Component
public class VersionDiffCalculator {

    private static final Comparator<TemplateLesson> SLOT_ORDER = Comparator.comparingInt(TemplateLesson::getWeekday)
            .thenComparingInt(TemplateLesson::getPairNumber)
            .thenComparing(TemplateLesson::getParity)
            .thenComparing(TemplateLesson::getGroupId)
            .thenComparingInt(TemplateLesson::getSubgroupNumber)
            .thenComparing(TemplateLesson::getId);

    /** Changes that turn {@code from} into {@code to}. */
    public Diff diff(Collection<TemplateLesson> from, Collection<TemplateLesson> to) {
        Map<Key, List<TemplateLesson>> fromByKey = byKey(from);
        Map<Key, List<TemplateLesson>> toByKey = byKey(to);
        List<TemplateLesson> removed = new ArrayList<>();
        List<TemplateLesson> added = new ArrayList<>();
        fromByKey.forEach((key, lessons) -> removed.addAll(surplus(lessons, toByKey.get(key))));
        toByKey.forEach((key, lessons) -> added.addAll(surplus(lessons, fromByKey.get(key))));

        List<Moved> moved = new ArrayList<>();
        Map<Identity, List<TemplateLesson>> addedByIdentity = added.stream()
                .collect(Collectors.groupingBy(Identity::of, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        for (TemplateLesson gone : removed.stream().sorted(SLOT_ORDER).toList()) {
            List<TemplateLesson> candidates = addedByIdentity.getOrDefault(Identity.of(gone), new ArrayList<>());
            candidates.stream()
                    .min(Comparator.comparingInt((TemplateLesson candidate) -> distance(gone, candidate))
                            .thenComparing(SLOT_ORDER))
                    .ifPresent(target -> {
                        candidates.remove(target);
                        moved.add(new Moved(gone, target));
                    });
        }
        Set<TemplateLesson> movedFrom = moved.stream().map(Moved::from).collect(Collectors.toSet());
        Set<TemplateLesson> movedTo = moved.stream().map(Moved::to).collect(Collectors.toSet());
        return new Diff(
                added.stream().filter(lesson -> !movedTo.contains(lesson)).sorted(SLOT_ORDER).toList(),
                removed.stream().filter(lesson -> !movedFrom.contains(lesson)).sorted(SLOT_ORDER).toList(),
                moved.stream().sorted(Comparator.comparing(Moved::from, SLOT_ORDER)).toList());
    }

    private static List<TemplateLesson> surplus(List<TemplateLesson> lessons, List<TemplateLesson> counterpart) {
        int common = counterpart == null ? 0 : Math.min(lessons.size(), counterpart.size());
        return lessons.stream().sorted(SLOT_ORDER).skip(common).toList();
    }

    private static Map<Key, List<TemplateLesson>> byKey(Collection<TemplateLesson> lessons) {
        return lessons.stream().collect(Collectors.groupingBy(Key::of, LinkedHashMap::new, Collectors.toList()));
    }

    private static int distance(TemplateLesson from, TemplateLesson to) {
        int days = Math.abs(from.getWeekday() - to.getWeekday()) * 20;
        int pairs = Math.abs(from.getPairNumber() - to.getPairNumber()) * 2;
        int parity = from.getParity() == to.getParity() ? 0 : 1;
        int room = Objects.equals(from.getRoomId(), to.getRoomId()) ? 0 : 1;
        return days + pairs + parity + room;
    }

    public record Diff(List<TemplateLesson> added, List<TemplateLesson> removed, List<Moved> moved) {
    }

    public record Moved(TemplateLesson from, TemplateLesson to) {
    }

    /** Full identity of a template lesson for the diff. */
    private record Key(UUID groupId, int subgroup, int weekday, int pair, Parity parity, UUID disciplineId,
                       UUID teacherId, UUID roomId) {

        static Key of(TemplateLesson lesson) {
            return new Key(lesson.getGroupId(), lesson.getSubgroupNumber(), lesson.getWeekday(),
                    lesson.getPairNumber(), lesson.getParity(), lesson.getDisciplineId(), lesson.getTeacherId(),
                    lesson.getRoomId());
        }
    }

    /** What stays the same when a lesson is moved. */
    private record Identity(UUID groupId, int subgroup, UUID disciplineId, UUID teacherId) {

        static Identity of(TemplateLesson lesson) {
            return new Identity(lesson.getGroupId(), lesson.getSubgroupNumber(), lesson.getDisciplineId(),
                    lesson.getTeacherId());
        }
    }
}
