package ua.vtc.rozklad.core.load.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Derives weekly load from the lessons of a timetable version (DOMAIN §12, TIMETABLE_DERIVED): a
 * lesson every week (BOTH) is one pair a week, a numerator- or denominator-only lesson is half a
 * pair. Per group × discipline the whole-group lessons give one item and the subgroup lessons a
 * second, split item (each subgroup gets as many pairs as the busiest one, with its own teacher).
 */
final class LoadDerivation {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private LoadDerivation() {
    }

    static List<Draft> derive(List<TemplateLessonRow> lessons, int hoursPerPair) {
        Map<GroupDiscipline, List<TemplateLessonRow>> byGroupDiscipline = new LinkedHashMap<>();
        lessons.forEach(lesson -> byGroupDiscipline
                .computeIfAbsent(new GroupDiscipline(lesson.groupId(), lesson.disciplineId()), key -> new ArrayList<>())
                .add(lesson));
        List<Draft> drafts = new ArrayList<>();
        byGroupDiscipline.forEach((key, rows) -> {
            List<TemplateLessonRow> whole = rows.stream().filter(row -> row.subgroup() == 0).toList();
            List<TemplateLessonRow> subgroups = rows.stream().filter(row -> row.subgroup() > 0).toList();
            if (!whole.isEmpty()) {
                BigDecimal pairs = pairs(whole);
                Map<Integer, UUID> teachers = new TreeMap<>();
                mostFrequentTeacher(whole).ifPresent(teacher -> teachers.put(0, teacher));
                drafts.add(new Draft(key.groupId(), key.disciplineId(), false, kindOf(whole), hours(pairs, hoursPerPair),
                        teachers));
            }
            if (!subgroups.isEmpty()) {
                Map<Integer, List<TemplateLessonRow>> bySubgroup = subgroups.stream()
                        .collect(Collectors.groupingBy(TemplateLessonRow::subgroup, TreeMap::new, Collectors.toList()));
                BigDecimal pairs = bySubgroup.values().stream().map(LoadDerivation::pairs)
                        .max(Comparator.naturalOrder()).orElseThrow();
                Map<Integer, UUID> teachers = new TreeMap<>();
                bySubgroup.forEach((subgroup, list) -> mostFrequentTeacher(list)
                        .ifPresent(teacher -> teachers.put(subgroup, teacher)));
                drafts.add(new Draft(key.groupId(), key.disciplineId(), true, kindOf(subgroups),
                        hours(pairs, hoursPerPair), teachers));
            }
        });
        return drafts;
    }

    /** BOTH = 1 pair a week, NUMERATOR / DENOMINATOR = ½. */
    static BigDecimal pairs(List<TemplateLessonRow> lessons) {
        return lessons.stream()
                .map(lesson -> lesson.parity() == Parity.BOTH ? BigDecimal.ONE : HALF)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal hours(BigDecimal pairs, int hoursPerPair) {
        return pairs.multiply(BigDecimal.valueOf(hoursPerPair)).setScale(1, java.math.RoundingMode.HALF_UP);
    }

    /** The teacher with the most pairs; ties go to the smaller id so the result is stable. */
    private static java.util.Optional<UUID> mostFrequentTeacher(List<TemplateLessonRow> lessons) {
        Map<UUID, BigDecimal> weight = new TreeMap<>();
        lessons.stream().filter(lesson -> lesson.teacherId() != null).forEach(lesson -> weight.merge(lesson.teacherId(),
                lesson.parity() == Parity.BOTH ? BigDecimal.ONE : HALF, BigDecimal::add));
        return weight.entrySet().stream()
                .max(Map.Entry.<UUID, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey);
    }

    private static LessonKind kindOf(List<TemplateLessonRow> lessons) {
        List<LessonKind> kinds = lessons.stream().map(TemplateLessonRow::lessonKind).filter(Objects::nonNull)
                .distinct().toList();
        return kinds.size() == 1 ? kinds.getFirst() : LessonKind.MIXED;
    }

    /** One lesson of the weekly template, read without depending on the timetable module. */
    record TemplateLessonRow(UUID groupId, int subgroup, UUID disciplineId, UUID teacherId, Parity parity,
                             LessonKind lessonKind) {
    }

    /**
     * A derived load item.
     *
     * @param teachers subgroup → teacher (0 for a whole-group item)
     */
    record Draft(UUID groupId, UUID disciplineId, boolean split, LessonKind lessonKind, BigDecimal hoursPerWeek,
                 Map<Integer, UUID> teachers) {

        Key key() {
            return new Key(groupId, disciplineId, split);
        }
    }

    /** Natural key of a derived item: group × discipline × split. */
    record Key(UUID groupId, UUID disciplineId, boolean split) {
    }

    private record GroupDiscipline(UUID groupId, UUID disciplineId) {
    }
}
