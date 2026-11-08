package ua.vtc.rozklad.importer.normalize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.DisciplineItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.GroupItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.RoomItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.TeacherItem;
import ua.vtc.rozklad.importer.normalize.Resolution.Outcome;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.ScoredRef;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.PersonName;
import ua.vtc.rozklad.importer.text.RoomNames;
import ua.vtc.rozklad.importer.text.Similarity;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.timetable.KnownDisciplines;

/**
 * Matches draft entities against the core catalog (DOMAIN §10). Teachers: alias or exact
 * name → surname with compatible initials → Jaro-Winkler ≥ 0.92 on the surname as suggestions
 * only. Disciplines: abbreviation key of short name or alias → full name (conjunctions «та», «й»,
 * «і» are one word) → close names as suggestions; a full name from a curriculum or a program is
 * also suggested for the catalog entry it is written short as: the same name without the
 * qualifier in brackets («Іноземна мова (за професійним спрям.)» → «Іноземна мова»), its initials
 * («Комп'ютерні мережі» → «КМ», «Економіка та управління ІТ-проєктами» → «ЕУ», «ЕУІТ») or, for
 * a one-word name, the letters of the abbreviation in order («Кібербезпека» → «КБ»). Groups by
 * canonical code, rooms by canonical name. Characters a reader could not decode (U+FFFD) are
 * left out when comparing. Admin mappings override all of it. Fuzzy results never bind
 * automatically: they become suggestions that need a decision.
 */
@Component
public class NormalizationEngine {

    private static final int MAX_SUGGESTIONS = 5;
    private static final double EXACT = 1.0;
    private static final double NAME_WITH_INITIALS = 0.95;
    private static final double SURNAME_ONLY = 0.9;
    private static final double WITHOUT_QUALIFIER = 0.95;
    private static final double INITIALS = 0.93;
    private static final double INITIALS_PREFIX = 0.9;
    private static final double NAME_PREFIX = 0.9;
    private static final double LETTERS_IN_ORDER = 0.88;
    private static final Pattern QUALIFIER = Pattern.compile("\\([^)]*\\)?");
    private static final Pattern NAME_NOISE = Pattern.compile("[.,;:«»\"“”]");
    private static final Pattern CONJUNCTION = Pattern.compile("(?<=\\s)(?:та|й)(?=\\s)");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s\\-–—/]+");
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "та", "і", "й", "в", "у", "з", "із", "зі", "на", "до", "за", "по", "від", "для", "як", "а", "о");

    public NormalizationResult normalize(List<EntityCandidate> entities, CatalogSnapshot catalog,
                                         List<MappingDecision> mappings) {
        Map<String, MappingDecision> decisions = new HashMap<>();
        mappings.forEach(decision -> decisions.put(decision.key(), decision));
        CatalogIndex index = new CatalogIndex(catalog);
        List<Resolution> resolutions = new ArrayList<>();
        for (EntityCandidate entity : entities) {
            MappingDecision decision = decisions.get(entity.key());
            resolutions.add(decision != null ? decide(entity, decision, catalog) : match(entity, index));
        }
        return new NormalizationResult(resolutions, resolutions.stream().map(NormalizationEngine::row).toList());
    }

    private Resolution match(EntityCandidate entity, CatalogIndex index) {
        return switch (entity.type()) {
            case TEACHER -> matchTeacher(entity, index);
            case DISCIPLINE -> matchDiscipline(entity, index);
            case GROUP -> matchGroup(entity, index);
            case ROOM -> matchRoom(entity, index);
        };
    }

    private static Resolution decide(EntityCandidate entity, MappingDecision decision, CatalogSnapshot catalog) {
        if (!decision.mapsToExisting()) {
            return new Resolution(entity, Outcome.NEW, null, List.of(), decision);
        }
        Optional<ScoredRef> target = switch (entity.type()) {
            case TEACHER -> catalog.teacher(decision.targetId()).map(item -> ref(item, EXACT));
            case DISCIPLINE -> catalog.discipline(decision.targetId()).map(item -> ref(item, EXACT));
            case GROUP -> catalog.group(decision.targetId()).map(item -> ref(item, EXACT));
            case ROOM -> catalog.room(decision.targetId()).map(item -> ref(item, EXACT));
        };
        return target.map(ref -> new Resolution(entity, Outcome.MAPPED, ref, List.of(), decision))
                .orElseGet(() -> new Resolution(entity, Outcome.INVALID_MAPPING, null, List.of(), decision));
    }

    // ───────────────────────── викладачі ─────────────────────────

    private static Resolution matchTeacher(EntityCandidate entity, CatalogIndex index) {
        Set<TeacherItem> byText = new LinkedHashSet<>();
        allSpellings(entity).map(TextNormalizer::key)
                .forEach(key -> byText.addAll(index.teachersByText.getOrDefault(key, List.of())));
        if (!byText.isEmpty()) {
            return decided(entity, byText.stream().map(item -> ref(item, EXACT)).toList());
        }
        PersonName name = PersonName.parse(readable(entity.raw()));
        if (name == null) {
            return new Resolution(entity, Outcome.NEW, null, List.of(), null);
        }
        List<IndexedTeacher> sameSurname = index.teachers.stream()
                .filter(teacher -> teacher.name().surnameKey().equals(name.surnameKey())
                        && teacher.name().initialsCompatibleWith(name))
                .toList();
        if (!sameSurname.isEmpty()) {
            double score = name.hasInitials() ? NAME_WITH_INITIALS : SURNAME_ONLY;
            return decided(entity, sameSurname.stream().map(teacher -> ref(teacher.item(), score)).toList());
        }
        List<ScoredRef> similar = index.teachers.stream()
                .filter(teacher -> teacher.name().initialsCompatibleWith(name)
                        && !Similarity.genderPair(teacher.name().surnameKey(), name.surnameKey()))
                .map(teacher -> ref(teacher.item(),
                        Similarity.jaroWinkler(teacher.name().surnameKey(), name.surnameKey())))
                .filter(ref -> ref.score() >= Similarity.SURNAME_THRESHOLD)
                .sorted(Comparator.comparingDouble(ScoredRef::score).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
        return suggestionsOrNew(entity, similar);
    }

    // ───────────────────────── дисципліни ─────────────────────────

    private static Resolution matchDiscipline(EntityCandidate entity, CatalogIndex index) {
        Set<DisciplineItem> byAbbreviation = new LinkedHashSet<>();
        allSpellings(entity).map(TextNormalizer::abbreviationKey)
                .forEach(key -> byAbbreviation.addAll(index.disciplinesByAbbreviation.getOrDefault(key, List.of())));
        if (!byAbbreviation.isEmpty()) {
            return decided(entity, byAbbreviation.stream().map(item -> ref(item, EXACT)).toList());
        }
        String fullName = readable(entity.fullName() != null ? entity.fullName()
                : KnownDisciplines.fullName(entity.raw()).orElse(entity.raw()));
        List<DisciplineItem> byName = index.disciplinesByName.getOrDefault(nameKey(fullName), List.of());
        if (!byName.isEmpty()) {
            return decided(entity, byName.stream().map(item -> ref(item, NAME_WITH_INITIALS)).toList());
        }
        String nameKey = TextNormalizer.key(fullName);
        String abbreviationKey = TextNormalizer.abbreviationKey(readable(entity.raw()));
        Map<UUID, ScoredRef> similar = new HashMap<>();
        index.disciplines.stream()
                .map(item -> ref(item, Math.max(
                        Similarity.jaroWinkler(nameKey, TextNormalizer.key(item.name())),
                        abbreviationKey.length() >= 5
                                ? Similarity.jaroWinkler(abbreviationKey, TextNormalizer.abbreviationKey(item.shortName()))
                                : 0)))
                .filter(ref -> ref.score() >= Similarity.SURNAME_THRESHOLD)
                .forEach(ref -> similar.merge(ref.id(), ref, NormalizationEngine::better));
        if (namedInFull(entity)) {
            shortForms(fullName, index).forEach(ref -> similar.merge(ref.id(), ref, NormalizationEngine::better));
        }
        return suggestionsOrNew(entity, similar.values().stream()
                .sorted(Comparator.comparingDouble(ScoredRef::score).reversed().thenComparing(ScoredRef::label))
                .limit(MAX_SUGGESTIONS)
                .toList());
    }

    private static ScoredRef better(ScoredRef a, ScoredRef b) {
        return a.score() >= b.score() ? a : b;
    }

    /**
     * The draft writes the discipline by its full name (curriculum, program), not by an
     * abbreviation of the timetable: only then are short catalog forms looked for.
     */
    private static boolean namedInFull(EntityCandidate entity) {
        return entity.fullName() != null
                && TextNormalizer.key(entity.fullName()).equals(TextNormalizer.key(entity.raw()));
    }

    /** Catalog entries the full name is written short as (see the class comment); suggestions only. */
    private static List<ScoredRef> shortForms(String fullName, CatalogIndex index) {
        String base = TextNormalizer.clean(QUALIFIER.matcher(fullName).replaceAll(" "));
        String baseKey = nameKey(base);
        boolean qualified = !baseKey.equals(nameKey(fullName));
        List<String> words = WORD_SEPARATOR.splitAsStream(NAME_NOISE.matcher(base).replaceAll(" "))
                .filter(word -> !word.isBlank())
                .filter(word -> !FUNCTION_WORDS.contains(word.toLowerCase(TextNormalizer.UK)))
                .toList();
        StringBuilder initials = new StringBuilder();
        for (String word : words) {
            boolean acronym = word.length() >= 2 && word.length() <= 4 && word.equals(word.toUpperCase(TextNormalizer.UK))
                    && word.chars().allMatch(Character::isLetter);
            String lower = TextNormalizer.key(word);
            initials.append(acronym ? lower : lower.substring(0, 1));
        }
        String singleWord = words.size() == 1 ? TextNormalizer.key(words.getFirst()) : null;
        List<ScoredRef> refs = new ArrayList<>();
        for (DisciplineItem item : index.disciplines) {
            double score = 0;
            String itemName = item.name() == null ? "" : nameKey(item.name());
            if (qualified && itemName.equals(baseKey)) {
                score = WITHOUT_QUALIFIER;
            } else if (!itemName.isEmpty() && itemName.contains(" ") && baseKey.startsWith(itemName + " ")) {
                score = NAME_PREFIX;
            }
            for (String abbreviation : index.abbreviationsOf(item)) {
                if (abbreviation.length() < 2 || abbreviation.length() > 6
                        || !abbreviation.chars().allMatch(Character::isLetter)) {
                    continue;
                }
                if (words.size() >= 2 && initials.toString().equals(abbreviation)) {
                    score = Math.max(score, INITIALS);
                } else if (words.size() >= 2 && initials.length() > abbreviation.length()
                        && initials.toString().startsWith(abbreviation)) {
                    score = Math.max(score, INITIALS_PREFIX);
                } else if (singleWord != null && singleWord.length() >= 6 && lettersInOrder(abbreviation, singleWord)) {
                    score = Math.max(score, LETTERS_IN_ORDER);
                }
            }
            if (score > 0) {
                refs.add(ref(item, score));
            }
        }
        return refs;
    }

    /** «кб» in «кібербезпека»: the same first letter, the rest in order. */
    private static boolean lettersInOrder(String abbreviation, String word) {
        if (abbreviation.charAt(0) != word.charAt(0)) {
            return false;
        }
        int at = 1;
        for (int i = 1; i < abbreviation.length(); i++) {
            at = word.indexOf(abbreviation.charAt(i), at);
            if (at < 0) {
                return false;
            }
            at++;
        }
        return true;
    }

    /** Name comparison key: {@link TextNormalizer#key}, the conjunctions «та» and «й» written as «і». */
    static String nameKey(String name) {
        return CONJUNCTION.matcher(TextNormalizer.key(name)).replaceAll("і");
    }

    /** The text without U+FFFD (a character the reader could not decode), for comparisons only. */
    static String readable(String text) {
        return text == null ? null : TextNormalizer.clean(text.replace("\uFFFD", ""));
    }

    // ───────────────────────── групи та аудиторії ─────────────────────────

    private static Resolution matchGroup(EntityCandidate entity, CatalogIndex index) {
        String raw = readable(entity.raw());
        String code = GroupCode.parse(raw).map(GroupCode::code).orElse(raw);
        GroupItem item = index.groupsByCode.get(code);
        return item == null
                ? new Resolution(entity, Outcome.NEW, null, List.of(), null)
                : new Resolution(entity, Outcome.MATCHED, ref(item, EXACT), List.of(), null);
    }

    private static Resolution matchRoom(EntityCandidate entity, CatalogIndex index) {
        RoomItem item = index.roomsByKey.get(RoomNames.key(readable(entity.raw())));
        return item == null
                ? new Resolution(entity, Outcome.NEW, null, List.of(), null)
                : new Resolution(entity, Outcome.MATCHED, ref(item, EXACT), List.of(), null);
    }

    // ───────────────────────── допоміжне ─────────────────────────

    private static Stream<String> allSpellings(EntityCandidate entity) {
        return Stream.concat(Stream.of(entity.raw()), entity.variants().stream()).map(NormalizationEngine::readable);
    }

    /** One candidate = matched; several = the admin chooses. */
    private static Resolution decided(EntityCandidate entity, List<ScoredRef> candidates) {
        List<ScoredRef> distinct = candidates.stream().distinct().toList();
        if (distinct.size() == 1) {
            return new Resolution(entity, Outcome.MATCHED, distinct.getFirst(), List.of(), null);
        }
        return new Resolution(entity, Outcome.AMBIGUOUS, null, distinct.stream().limit(MAX_SUGGESTIONS).toList(), null);
    }

    private static Resolution suggestionsOrNew(EntityCandidate entity, List<ScoredRef> similar) {
        return similar.isEmpty()
                ? new Resolution(entity, Outcome.NEW, null, List.of(), null)
                : new Resolution(entity, Outcome.AMBIGUOUS, null, similar, null);
    }

    /** Report row of one resolution (severity: OK matched, INFO new, WARN undecided, ERROR stale mapping). */
    public static ReportRow row(Resolution resolution) {
        EntityCandidate entity = resolution.entity();
        String noun = entity.type().label();
        return switch (resolution.outcome()) {
            case MATCHED -> new ReportRow(Severity.OK, entity.type().rowType(), entity.raw(), resolution.matched(), List.of(),
                    entity.location(), "Знайдено в каталозі.");
            case MAPPED -> new ReportRow(Severity.OK, entity.type().rowType(), entity.raw(), resolution.matched(), List.of(),
                    entity.location(), resolution.decision().addAlias()
                            ? "Зіставлено вручну; написання з файлу буде додано як псевдонім."
                            : "Зіставлено вручну.");
            case NEW -> new ReportRow(Severity.INFO, entity.type().rowType(), entity.raw(), null, List.of(), entity.location(),
                    resolution.decision() != null
                            ? "Буде створено новий запис за рішенням адміністратора."
                            : "Немає в каталозі — буде створено (" + noun + ").");
            case AMBIGUOUS -> new ReportRow(Severity.WARN, entity.type().rowType(), entity.raw(), null,
                    resolution.suggestions(), entity.location(),
                    "У каталозі є схожі записи — оберіть відповідник або підтвердіть створення нового.");
            case INVALID_MAPPING -> new ReportRow(Severity.ERROR, entity.type().rowType(), entity.raw(), null, List.of(),
                    entity.location(), "Обраного відповідника вже немає в каталозі — зіставте ще раз.");
        };
    }

    static ScoredRef ref(TeacherItem item, double score) {
        return new ScoredRef(item.id(), item.shortName(), score);
    }

    static ScoredRef ref(DisciplineItem item, double score) {
        boolean sameName = item.name() == null || item.name().equals(item.shortName());
        return new ScoredRef(item.id(), sameName ? item.shortName() : item.shortName() + " — " + item.name(), score);
    }

    static ScoredRef ref(GroupItem item, double score) {
        return new ScoredRef(item.id(), item.code(), score);
    }

    static ScoredRef ref(RoomItem item, double score) {
        return new ScoredRef(item.id(), item.name(), score);
    }

    private record IndexedTeacher(TeacherItem item, PersonName name) {
    }

    /** Precomputed keys of a catalog snapshot. */
    private static final class CatalogIndex {

        private final List<IndexedTeacher> teachers = new ArrayList<>();
        private final Map<String, List<TeacherItem>> teachersByText = new HashMap<>();
        private final List<DisciplineItem> disciplines;
        private final Map<String, List<DisciplineItem>> disciplinesByAbbreviation = new HashMap<>();
        private final Map<String, List<DisciplineItem>> disciplinesByName = new HashMap<>();
        private final Map<UUID, List<String>> abbreviations = new HashMap<>();
        private final Map<String, GroupItem> groupsByCode = new HashMap<>();
        private final Map<String, RoomItem> roomsByKey = new HashMap<>();

        CatalogIndex(CatalogSnapshot catalog) {
            for (TeacherItem teacher : catalog.teachers()) {
                PersonName name = Optional.ofNullable(PersonName.parse(teacher.shortName()))
                        .orElseGet(() -> PersonName.parse(teacher.lastName()));
                if (name != null) {
                    teachers.add(new IndexedTeacher(teacher, name));
                }
                Stream.concat(Stream.of(teacher.shortName(), teacher.displayName()), teacher.aliases().stream())
                        .filter(text -> text != null && !text.isBlank())
                        .map(TextNormalizer::key).distinct()
                        .forEach(key -> add(teachersByText, key, teacher));
            }
            this.disciplines = catalog.disciplines();
            for (DisciplineItem discipline : disciplines) {
                List<String> keys = Stream.concat(Stream.of(discipline.shortName()), discipline.aliases().stream())
                        .filter(text -> text != null && !text.isBlank())
                        .map(TextNormalizer::abbreviationKey).distinct()
                        .toList();
                keys.forEach(key -> add(disciplinesByAbbreviation, key, discipline));
                abbreviations.put(discipline.id(), keys);
                if (discipline.name() != null) {
                    add(disciplinesByName, nameKey(discipline.name()), discipline);
                }
            }
            catalog.groups().forEach(group -> groupsByCode.put(
                    GroupCode.parse(group.code()).map(GroupCode::code).orElse(group.code()), group));
            catalog.rooms().forEach(room -> roomsByKey.put(RoomNames.key(room.name()), room));
        }

        List<String> abbreviationsOf(DisciplineItem discipline) {
            return abbreviations.getOrDefault(discipline.id(), List.of());
        }

        private static <T> void add(Map<String, List<T>> map, String key, T value) {
            List<T> values = map.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!values.contains(value)) {
                values.add(value);
            }
        }
    }

    /** Resolutions keyed like mappings ({@link EntityCandidate#key()}). */
    public record NormalizationResult(List<Resolution> resolutions, List<ReportRow> rows) {

        public Map<String, Resolution> byKey() {
            Map<String, Resolution> map = new HashMap<>();
            resolutions.forEach(resolution -> map.put(resolution.entity().key(), resolution));
            return map;
        }

        public Optional<Resolution> find(EntityType type, String raw) {
            return Optional.ofNullable(byKey().get(EntityCandidate.key(type, raw)));
        }
    }
}
