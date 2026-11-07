package ua.vtc.rozklad.importer.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.Similarity;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Consolidates discipline abbreviations of one workbook (DOMAIN §10). Spellings with the same
 * abbreviation key are one discipline («Укр.мова.» = «Укр.мова», «Фк» = «ФК»). Then:
 * <ul>
 *   <li>a rare key one typo away from a frequent key is a typo («Матет.» → «Матем.»);</li>
 *   <li>a truncated key that is the only prefix of another key merges with it («Укр.мов.» →
 *       «Укр.мова», «Інфом.» → «Інф.»); short ambiguous truncations («Вс.») merge only into the
 *       discipline taught by the same teacher;</li>
 *   <li>other close pairs («Вс.спц.» / «Вст.спц.») are reported as possible duplicates, not merged.</li>
 * </ul>
 */
final class DisciplineRegistry {

    private static final int RARE_MAX = 2;
    private static final int FREQUENT_MIN = 3;

    /** A discipline occurrence with the teacher (short name) of the same lesson. */
    record Occurrence(String raw, String teacher, String location) {
    }

    static final class Discipline {
        private final String key;
        private final Map<String, Integer> spellings = new LinkedHashMap<>();
        private final Set<String> teachers = new HashSet<>();
        private int count;
        private String firstLocation;
        private Discipline mergedInto;
        private final Set<String> keys = new HashSet<>();

        private Discipline(String key) {
            this.key = key;
            keys.add(key);
        }

        Discipline root() {
            Discipline current = this;
            while (current.mergedInto != null) {
                current = current.mergedInto;
            }
            return current;
        }

        /** Most frequent spelling (ties: longer, then alphabetical). */
        String shortName() {
            return spellings.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(entry -> entry.getKey().length())
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).orElseThrow();
        }

        List<String> variants() {
            return List.copyOf(spellings.keySet());
        }

        int count() {
            return count;
        }

        Set<String> keys() {
            return Set.copyOf(keys);
        }

        /**
         * A shortened word («Вс.», «Укр.мов», «Екол.») has lower-case letters; an acronym («ІТ»,
         * «ВСТР») is all capitals, and a longer acronym is a different discipline, not a truncation.
         */
        private boolean wordAbbreviation() {
            return spellings.keySet().stream().anyMatch(spelling -> !spelling.equals(spelling.toUpperCase(TextNormalizer.UK)));
        }
    }

    private final Issues issues;
    private final Map<String, Discipline> byKey = new LinkedHashMap<>();

    DisciplineRegistry(Issues issues) {
        this.issues = issues;
    }

    DisciplineRegistry build(List<Occurrence> occurrences) {
        for (Occurrence occurrence : occurrences) {
            String key = TextNormalizer.abbreviationKey(occurrence.raw());
            Discipline discipline = byKey.computeIfAbsent(key, Discipline::new);
            discipline.spellings.merge(TextNormalizer.clean(occurrence.raw()), 1, Integer::sum);
            if (occurrence.teacher() != null) {
                discipline.teachers.add(occurrence.teacher());
            }
            discipline.count++;
            if (discipline.firstLocation == null) {
                discipline.firstLocation = occurrence.location();
            }
        }
        mergeTypos();
        mergeTruncations();
        reportCloseSpellings();
        return this;
    }

    /** Canonical discipline of a raw spelling. */
    Discipline disciplineOf(String raw) {
        return byKey.get(TextNormalizer.abbreviationKey(raw)).root();
    }

    List<Discipline> roots() {
        return byKey.values().stream().filter(discipline -> discipline.mergedInto == null).toList();
    }

    private void mergeTypos() {
        for (Discipline rare : sortedByCount()) {
            if (rare.mergedInto != null || rare.count > RARE_MAX) {
                continue;
            }
            List<Discipline> targets = byKey.values().stream()
                    .filter(target -> target != rare && target.mergedInto == null && target.root().count >= FREQUENT_MIN)
                    .filter(target -> Similarity.likelyTypo(rare.key, target.key))
                    .toList();
            if (targets.size() == 1) {
                merge(rare, targets.getFirst(), "Ймовірна описка в назві дисципліни");
            }
        }
    }

    private void mergeTruncations() {
        for (Discipline shorter : sortedByCount()) {
            if (shorter.mergedInto != null || !shorter.wordAbbreviation()) {
                continue;
            }
            List<Discipline> extensions = byKey.values().stream()
                    .filter(longer -> longer != shorter && longer.mergedInto == null)
                    .filter(longer -> longer.key.length() > shorter.key.length() && longer.key.startsWith(shorter.key))
                    .filter(longer -> !Similarity.differInDigits(longer.key, shorter.key))
                    .toList();
            if (extensions.isEmpty()) {
                continue;
            }
            if (extensions.size() == 1 && shorter.key.length() >= 4) {
                Discipline longer = extensions.getFirst();
                if (shorter.count <= longer.count) {
                    merge(shorter, longer, "Скорочену назву дисципліни об'єднано з повнішою");
                } else {
                    merge(longer, shorter, "Довшу форму назви дисципліни об'єднано з поширенішою");
                }
                continue;
            }
            if (extensions.size() == 1 && shorter.key.length() == 3 && extensions.getFirst().count <= RARE_MAX) {
                merge(extensions.getFirst(), shorter, "Рідкісну довшу форму назви дисципліни об'єднано з поширенішою");
                continue;
            }
            if (shorter.count <= RARE_MAX) {
                List<Discipline> sameTeacher = extensions.stream()
                        .filter(longer -> longer.teachers.stream().anyMatch(shorter.teachers::contains))
                        .toList();
                if (sameTeacher.size() == 1) {
                    merge(shorter, sameTeacher.getFirst(),
                            "Обрізану назву дисципліни віднесено до тієї, яку веде той самий викладач");
                }
            }
        }
    }

    private void reportCloseSpellings() {
        List<Discipline> roots = roots();
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                Discipline a = roots.get(i);
                Discipline b = roots.get(j);
                // абревіатури з однієї літери різниці («ОПАП»/«ОВАП») — різні дисципліни, не підказуємо
                boolean close = a.key.length() >= 5 && b.key.length() >= 5
                        && (a.wordAbbreviation() || b.wordAbbreviation())
                        && Math.abs(a.key.length() - b.key.length()) <= 1
                        && Similarity.osaDistance(a.key, b.key) == 1
                        && !Similarity.differInDigits(a.key, b.key);
                if (close) {
                    issues.warn(RowType.DISCIPLINE, a.shortName() + " / " + b.shortName(), a.firstLocation,
                            "Можливо, «" + a.shortName() + "» і «" + b.shortName()
                                    + "» — та сама дисципліна. Імпортовано окремо; за потреби зіставте вручну.");
                }
            }
        }
    }

    private void merge(Discipline from, Discipline into, String reason) {
        Discipline target = into.root();
        from.mergedInto = target;
        target.keys.addAll(from.keys);
        from.spellings.forEach((spelling, count) -> target.spellings.merge(spelling, count, Integer::sum));
        target.teachers.addAll(from.teachers);
        target.count += from.count;
        issues.warn(RowType.DISCIPLINE, String.join(", ", from.spellings.keySet()), from.firstLocation,
                reason + ": «" + String.join("», «", from.spellings.keySet()) + "» → «" + target.shortName() + "».");
    }

    private List<Discipline> sortedByCount() {
        List<Discipline> sorted = new ArrayList<>(byKey.values());
        sorted.sort(Comparator.comparingInt((Discipline discipline) -> discipline.count)
                .thenComparing(discipline -> discipline.key));
        return sorted;
    }

    /** Spellings of all roots, for tests and diagnostics. */
    Map<String, List<String>> spellingsByShortName() {
        Map<String, List<String>> result = new HashMap<>();
        roots().forEach(root -> result.put(root.shortName(), root.variants()));
        return result;
    }
}
