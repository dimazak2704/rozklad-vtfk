package ua.vtc.rozklad.importer.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.PersonName;
import ua.vtc.rozklad.importer.text.Similarity;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Consolidates teacher names of one workbook into persons (DOMAIN §10). Lessons mostly carry bare
 * surnames («Вихристюк») or one initial («Харченко О.»); the curators row has full initials
 * («Вихристюк Ю.А.»). Rules, in order:
 * <ol>
 *   <li>a rare surname spelling (≤ 2 uses) one typo away from a frequent one (≥ 3 uses) is a typo
 *       («Герасменко» → «Герасименко», «Граболінська» → «Гарболінська»); masculine/feminine forms
 *       («Шидловський»/«Шидловська») and two frequent spellings («Марценюк»/«Марцинюк») stay apart;</li>
 *   <li>names with the same surname and compatible initials are one person; «Овчар Ін.» and
 *       «Овчар Ів.» are two people;</li>
 *   <li>a name that fits several persons (bare «Харченко» vs «Харченко О.А.» and «Харченко Г.Ю.»)
 *       goes to the one who teaches the same discipline elsewhere in the file; otherwise it stays a
 *       separate record marked for review.</li>
 * </ol>
 */
final class TeacherRegistry {

    private static final int RARE_SPELLING_MAX = 2;
    private static final int FREQUENT_SPELLING_MIN = 3;

    /** A name occurrence: in a lesson (with its discipline key) or in the curators row. */
    record Occurrence(PersonName name, String raw, String location, String disciplineKey, boolean curator) {
    }

    static final class Person {
        private final String surnameKey;
        private List<String> initials;
        private final Map<String, Integer> surnameSpellings = new HashMap<>();
        private final Map<String, Integer> variants = new LinkedHashMap<>();
        private final Set<String> disciplineKeys = new HashSet<>();
        private int lessonCount;
        private boolean ambiguous;
        private String shortName;
        private String displayName;

        private Person(String surnameKey, List<String> initials) {
            this.surnameKey = surnameKey;
            this.initials = initials;
        }

        String shortName() {
            return shortName;
        }

        String displayName() {
            return displayName;
        }

        String surname() {
            return surnameSpellings.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElseThrow();
        }

        String formattedInitials() {
            return initials.stream().map(initial -> initial + ".").collect(Collectors.joining());
        }

        List<String> variants() {
            return List.copyOf(variants.keySet());
        }

        int lessonCount() {
            return lessonCount;
        }

        boolean ambiguous() {
            return ambiguous;
        }

        private boolean compatible(List<String> other) {
            int common = Math.min(initials.size(), other.size());
            for (int i = 0; i < common; i++) {
                String a = initials.get(i).toLowerCase(TextNormalizer.UK);
                String b = other.get(i).toLowerCase(TextNormalizer.UK);
                if (!a.startsWith(b) && !b.startsWith(a)) {
                    return false;
                }
            }
            return true;
        }

        private void absorb(Occurrence occurrence) {
            if (occurrence.name().initials().size() > initials.size()) {
                initials = occurrence.name().initials();
            }
            surnameSpellings.merge(occurrence.name().surname(), 1, Integer::sum);
            // варіант без позначок «(п)»/«гр.»: «ХарченкоГ.» → «Харченко Г.», «Хорченко О.» лишається описом
            variants.merge(occurrence.name().shortForm(), 1, Integer::sum);
            if (!occurrence.curator()) {
                lessonCount++;
                if (occurrence.disciplineKey() != null) {
                    disciplineKeys.add(occurrence.disciplineKey());
                }
            }
        }
    }

    private final Issues issues;
    private final List<Person> persons = new ArrayList<>();
    private final Map<Occurrence, Person> assignment = new IdentityHashMap<>();
    private final Map<String, String> typoTargets = new HashMap<>();

    TeacherRegistry(Issues issues) {
        this.issues = issues;
    }

    /** Builds persons for all occurrences; afterwards {@link #personOf} resolves each occurrence. */
    TeacherRegistry build(List<Occurrence> occurrences) {
        detectTypos(occurrences);
        List<Occurrence> curators = occurrences.stream().filter(Occurrence::curator).toList();
        List<Occurrence> withInitials = occurrences.stream()
                .filter(occurrence -> !occurrence.curator() && occurrence.name().hasInitials())
                .sorted(Comparator.comparingInt((Occurrence occurrence) -> occurrence.name().initials().size()).reversed())
                .toList();
        List<Occurrence> bare = occurrences.stream()
                .filter(occurrence -> !occurrence.curator() && !occurrence.name().hasInitials())
                .toList();
        List<Occurrence> undecided = new ArrayList<>();
        for (List<Occurrence> batch : List.of(curators, withInitials, bare)) {
            for (Occurrence occurrence : batch) {
                List<Person> candidates = candidates(occurrence);
                if (candidates.size() > 1 && !occurrence.curator()) {
                    undecided.add(occurrence);
                } else {
                    assign(occurrence, candidates.isEmpty() ? newPerson(occurrence) : candidates.getFirst());
                }
            }
        }
        undecided.forEach(this::decideAmbiguous);
        name();
        return this;
    }

    Person personOf(Occurrence occurrence) {
        return assignment.get(occurrence);
    }

    List<Person> persons() {
        return List.copyOf(persons);
    }

    private String effectiveKey(PersonName name) {
        String key = name.surnameKey();
        return typoTargets.getOrDefault(key, key);
    }

    private void detectTypos(List<Occurrence> occurrences) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> spelling = new HashMap<>();
        Map<String, String> location = new HashMap<>();
        for (Occurrence occurrence : occurrences) {
            String key = occurrence.name().surnameKey();
            counts.merge(key, 1, Integer::sum);
            spelling.putIfAbsent(key, occurrence.name().surname());
            location.putIfAbsent(key, occurrence.location());
        }
        for (Map.Entry<String, Integer> rare : counts.entrySet()) {
            if (rare.getValue() > RARE_SPELLING_MAX) {
                continue;
            }
            List<String> targets = counts.entrySet().stream()
                    .filter(frequent -> frequent.getValue() >= FREQUENT_SPELLING_MIN)
                    .map(Map.Entry::getKey)
                    .filter(frequent -> Similarity.likelyTypo(rare.getKey(), frequent))
                    .toList();
            if (targets.size() == 1) {
                typoTargets.put(rare.getKey(), targets.getFirst());
                issues.warn(RowType.TEACHER, spelling.get(rare.getKey()), location.get(rare.getKey()),
                        "Ймовірна описка в прізвищі: «" + spelling.get(rare.getKey()) + "» об'єднано з «"
                                + spelling.get(targets.getFirst()) + "».");
            }
        }
    }

    private List<Person> candidates(Occurrence occurrence) {
        String key = effectiveKey(occurrence.name());
        return persons.stream()
                .filter(person -> person.surnameKey.equals(key) && !person.ambiguous)
                .filter(person -> person.compatible(occurrence.name().initials()))
                .toList();
    }

    private Person newPerson(Occurrence occurrence) {
        Person person = new Person(effectiveKey(occurrence.name()), occurrence.name().initials());
        persons.add(person);
        return person;
    }

    private void assign(Occurrence occurrence, Person person) {
        person.absorb(occurrence);
        assignment.put(occurrence, person);
    }

    /** Several persons fit: the one teaching the same discipline in the file wins, else a review record. */
    private void decideAmbiguous(Occurrence occurrence) {
        List<Person> candidates = candidates(occurrence);
        List<Person> sameDiscipline = candidates.stream()
                .filter(person -> person.disciplineKeys.contains(occurrence.disciplineKey()))
                .toList();
        if (sameDiscipline.size() == 1) {
            Person person = sameDiscipline.getFirst();
            assign(occurrence, person);
            issues.info(RowType.TEACHER, occurrence.raw(), occurrence.location(), "«" + occurrence.raw()
                    + "» підходить кільком викладачам; віднесено до того, хто веде цю дисципліну в інших заняттях.");
            return;
        }
        Person review = persons.stream()
                .filter(person -> person.ambiguous && person.surnameKey.equals(effectiveKey(occurrence.name()))
                        && person.initials.equals(occurrence.name().initials()))
                .findFirst()
                .orElseGet(() -> {
                    Person person = newPerson(occurrence);
                    person.ambiguous = true;
                    issues.warn(RowType.TEACHER, occurrence.raw(), occurrence.location(), "«" + occurrence.raw()
                            + "» може бути кількома викладачами (" + candidates.stream()
                            .map(candidate -> candidate.surname() + " " + candidate.formattedInitials())
                            .map(String::strip).collect(Collectors.joining(", "))
                            + ") — створено окремий запис для перевірки.");
                    return person;
                });
        assign(occurrence, review);
    }

    /** Canonical short and display names; display names gain an initial when a surname is shared. */
    private void name() {
        Map<String, Long> sharedSurnames = persons.stream()
                .collect(Collectors.groupingBy(person -> person.surnameKey, Collectors.counting()));
        Set<String> usedShortNames = new HashSet<>();
        for (Person person : persons) {
            String surname = person.surname();
            String shortName = person.initials.isEmpty() ? surname : surname + " " + person.formattedInitials();
            if (!usedShortNames.add(TextNormalizer.key(shortName))) {
                // збіг можливий лише для записів «на перевірку»; позначаємо їх явно
                shortName = shortName + " (перевірити)";
                usedShortNames.add(TextNormalizer.key(shortName));
            }
            person.shortName = shortName;
            boolean shared = sharedSurnames.get(person.surnameKey) > 1;
            person.displayName = shared && !person.initials.isEmpty()
                    ? surname + " " + person.initials.getFirst() + "."
                    : surname;
        }
    }
}
