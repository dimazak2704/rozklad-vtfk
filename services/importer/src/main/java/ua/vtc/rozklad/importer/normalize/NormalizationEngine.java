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
 * only. Disciplines: abbreviation key of short name or alias → full name → close names as
 * suggestions. Groups by canonical code, rooms by canonical name. Admin mappings override all of
 * it. Fuzzy results never bind automatically: they become suggestions that need a decision.
 */
@Component
public class NormalizationEngine {

    private static final int MAX_SUGGESTIONS = 5;
    private static final double EXACT = 1.0;
    private static final double NAME_WITH_INITIALS = 0.95;
    private static final double SURNAME_ONLY = 0.9;

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
        PersonName name = PersonName.parse(entity.raw());
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
        String fullName = entity.fullName() != null ? entity.fullName()
                : KnownDisciplines.fullName(entity.raw()).orElse(entity.raw());
        List<DisciplineItem> byName = index.disciplinesByName.getOrDefault(TextNormalizer.key(fullName), List.of());
        if (!byName.isEmpty()) {
            return decided(entity, byName.stream().map(item -> ref(item, NAME_WITH_INITIALS)).toList());
        }
        String nameKey = TextNormalizer.key(fullName);
        String abbreviationKey = TextNormalizer.abbreviationKey(entity.raw());
        List<ScoredRef> similar = index.disciplines.stream()
                .map(item -> ref(item, Math.max(
                        Similarity.jaroWinkler(nameKey, TextNormalizer.key(item.name())),
                        abbreviationKey.length() >= 5
                                ? Similarity.jaroWinkler(abbreviationKey, TextNormalizer.abbreviationKey(item.shortName()))
                                : 0)))
                .filter(ref -> ref.score() >= Similarity.SURNAME_THRESHOLD)
                .sorted(Comparator.comparingDouble(ScoredRef::score).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
        return suggestionsOrNew(entity, similar);
    }

    // ───────────────────────── групи та аудиторії ─────────────────────────

    private static Resolution matchGroup(EntityCandidate entity, CatalogIndex index) {
        String code = GroupCode.parse(entity.raw()).map(GroupCode::code).orElse(TextNormalizer.clean(entity.raw()));
        GroupItem item = index.groupsByCode.get(code);
        return item == null
                ? new Resolution(entity, Outcome.NEW, null, List.of(), null)
                : new Resolution(entity, Outcome.MATCHED, ref(item, EXACT), List.of(), null);
    }

    private static Resolution matchRoom(EntityCandidate entity, CatalogIndex index) {
        RoomItem item = index.roomsByKey.get(RoomNames.key(entity.raw()));
        return item == null
                ? new Resolution(entity, Outcome.NEW, null, List.of(), null)
                : new Resolution(entity, Outcome.MATCHED, ref(item, EXACT), List.of(), null);
    }

    // ───────────────────────── допоміжне ─────────────────────────

    private static Stream<String> allSpellings(EntityCandidate entity) {
        return Stream.concat(Stream.of(entity.raw()), entity.variants().stream());
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
                Stream.concat(Stream.of(discipline.shortName()), discipline.aliases().stream())
                        .filter(text -> text != null && !text.isBlank())
                        .map(TextNormalizer::abbreviationKey).distinct()
                        .forEach(key -> add(disciplinesByAbbreviation, key, discipline));
                if (discipline.name() != null) {
                    add(disciplinesByName, TextNormalizer.key(discipline.name()), discipline);
                }
            }
            catalog.groups().forEach(group -> groupsByCode.put(
                    GroupCode.parse(group.code()).map(GroupCode::code).orElse(group.code()), group));
            catalog.rooms().forEach(room -> roomsByKey.put(RoomNames.key(room.name()), room));
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
