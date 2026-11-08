package ua.vtc.rozklad.importer.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CatalogKeys;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.ImportKind;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.text.PersonName;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * TIMETABLE_XLSX: parses the workbook, offers its teachers, disciplines, groups and rooms for
 * matching and applies in two checkpointed steps: (1) {@code POST /api/admin/import/catalog} with
 * the new entities and aliases, (2) {@code POST /api/admin/import/timetable} with all lessons → a
 * DRAFT version. Bell profiles stay in the draft (CONTRACTS has no import endpoint for them).
 */
@Component
public class TimetableImportHandler implements ImportHandler {

    static final String STEP_CATALOG = "catalog";
    static final String STEP_TIMETABLE = "timetable";
    private static final int VERSION_NAME_LENGTH = 100;

    private final TimetableWorkbookParser parser;
    private final CoreImportClient core;
    private final JsonMapper jsonMapper;

    public TimetableImportHandler(TimetableWorkbookParser parser, CoreImportClient core, JsonMapper jsonMapper) {
        this.parser = parser;
        this.core = core;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.TIMETABLE_XLSX;
    }

    @Override
    public boolean acceptUpload(FileType type, JobOptions options) {
        if (type != FileType.XLSX) {
            throw ProblemException.unsupportedFile("Для імпорту розкладу потрібен файл Excel (XLSX), а завантажено "
                    + type.label() + ".");
        }
        return false;
    }

    @Override
    public ParseOutcome parse(ParseInput input) {
        TimetableWorkbookParser.Result result = parser.parse(input.content(), input.options().sheet());
        return new ParseOutcome(result.draft(), result.issues());
    }

    @Override
    public List<EntityCandidate> entities(JsonNode draftJson, JobOptions options) {
        TimetableDraft draft = jsonMapper.treeToValue(draftJson, TimetableDraft.class);
        Map<String, String> teacherLocations = new HashMap<>();
        Map<String, String> disciplineLocations = new HashMap<>();
        Map<String, String> roomLocations = new HashMap<>();
        for (TimetableDraft.Lesson lesson : draft.lessons()) {
            if (lesson.teacher() != null) {
                teacherLocations.putIfAbsent(lesson.teacher(), lesson.location());
            }
            disciplineLocations.putIfAbsent(lesson.discipline(), lesson.location());
            if (lesson.room() != null) {
                roomLocations.putIfAbsent(lesson.room(), lesson.location());
            }
        }
        List<EntityCandidate> entities = new ArrayList<>();
        draft.teachers().forEach(teacher -> entities.add(new EntityCandidate(EntityType.TEACHER, teacher.shortName(),
                teacher.variants(), null, teacherLocations.get(teacher.shortName()))));
        draft.disciplines().forEach(discipline -> entities.add(new EntityCandidate(EntityType.DISCIPLINE,
                discipline.shortName(), discipline.variants(), discipline.needsReview() ? null : discipline.name(),
                disciplineLocations.get(discipline.shortName()))));
        draft.groups().forEach(group -> entities.add(new EntityCandidate(EntityType.GROUP, group.code(), List.of(group.raw()),
                null, group.location())));
        draft.rooms().forEach(room -> entities.add(new EntityCandidate(EntityType.ROOM, room.name(), room.variants(), null,
                roomLocations.get(room.name()))));
        return entities;
    }

    @Override
    public JsonNode apply(ApplyInput input) {
        UUID semesterId = input.semesterId();
        if (semesterId == null) {
            throw new ApplyException("SEMESTER_REQUIRED", "Оберіть семестр, у який імпортувати розклад.");
        }
        CatalogSnapshot catalog = input.catalog();
        if (!catalog.semesters().isEmpty() && !catalog.hasSemester(semesterId)) {
            throw new ApplyException("SEMESTER_NOT_FOUND", "Обраного семестру немає на сервері розкладу — оберіть інший семестр.");
        }
        TimetableDraft draft = jsonMapper.treeToValue(input.draft(), TimetableDraft.class);
        CatalogKeys keys = new CatalogKeys(input.normalization(), catalog);
        if (input.steps().done(STEP_CATALOG) == null) {
            CoreImportPayloads.CatalogRequest request = catalogRequest(draft, keys, input.normalization().resolutions().stream()
                    .map(resolution -> resolution.decision()).filter(java.util.Objects::nonNull).toList());
            JsonNode result = request.isEmpty()
                    ? jsonMapper.createObjectNode().put("skipped", true)
                    : core.importCatalog(request, input.steps().idempotencyKey(STEP_CATALOG));
            input.steps().finished(STEP_CATALOG, result);
        }
        if (input.steps().done(STEP_TIMETABLE) == null) {
            String name = input.options().versionName() != null
                    ? input.options().versionName()
                    : truncate("Імпорт з «" + input.fileName() + "»", VERSION_NAME_LENGTH);
            List<CoreImportPayloads.TimetableLesson> lessons = draft.lessons().stream()
                    .map(lesson -> new CoreImportPayloads.TimetableLesson(lesson.weekday(), lesson.pair(), lesson.parity(),
                            keys.key(EntityType.GROUP, lesson.groupCode(), "code"), lesson.subgroup(),
                            keys.key(EntityType.DISCIPLINE, lesson.discipline(), "shortName"),
                            keys.key(EntityType.TEACHER, lesson.teacher(), "shortName"),
                            keys.key(EntityType.ROOM, lesson.room(), "name"), false))
                    .toList();
            JsonNode result = core.importTimetable(new CoreImportPayloads.TimetableRequest(semesterId, name, lessons),
                    input.steps().idempotencyKey(STEP_TIMETABLE));
            input.steps().finished(STEP_TIMETABLE, result);
        }
        return input.steps().all();
    }

    static CoreImportPayloads.CatalogRequest catalogRequest(TimetableDraft draft, CatalogKeys keys,
                                                            List<MappingDecision> decisions) {
        List<CoreImportPayloads.Teacher> teachers = new ArrayList<>();
        List<CoreImportPayloads.Alias> aliases = new ArrayList<>();
        Set<String> aliasKeys = new LinkedHashSet<>();
        for (TimetableDraft.Teacher teacher : draft.teachers()) {
            if (!keys.isNew(EntityType.TEACHER, teacher.shortName())) {
                continue;
            }
            var resolution = keys.resolution(EntityType.TEACHER, teacher.shortName()).orElse(null);
            String shortName = keys.key(EntityType.TEACHER, teacher.shortName(), "shortName");
            String displayName = resolution == null ? teacher.displayName()
                    : CatalogKeys.override(resolution, "displayName").orElse(teacher.displayName());
            String lastName = resolution == null ? teacher.lastName()
                    : CatalogKeys.override(resolution, "lastName").orElse(teacher.lastName());
            teachers.add(new CoreImportPayloads.Teacher(shortName, displayName, lastName, null, null, teacher.needsReview()));
            String surnameKey = TextNormalizer.key(teacher.lastName());
            for (String variant : teacher.variants()) {
                PersonName name = PersonName.parse(variant);
                // псевдонім лише для описок у прізвищі: «Хорченко О.» → «Харченко О.А.»
                if (name != null && !name.surnameKey().equals(surnameKey) && aliasKeys.add("T:" + TextNormalizer.key(variant))) {
                    aliases.add(new CoreImportPayloads.Alias("TEACHER", variant, shortName));
                }
            }
        }
        List<CoreImportPayloads.Discipline> disciplines = new ArrayList<>();
        for (TimetableDraft.Discipline discipline : draft.disciplines()) {
            if (!keys.isNew(EntityType.DISCIPLINE, discipline.shortName())) {
                continue;
            }
            var resolution = keys.resolution(EntityType.DISCIPLINE, discipline.shortName()).orElse(null);
            String shortName = keys.key(EntityType.DISCIPLINE, discipline.shortName(), "shortName");
            String name = resolution == null ? discipline.name()
                    : CatalogKeys.override(resolution, "name").orElse(discipline.name());
            disciplines.add(new CoreImportPayloads.Discipline(shortName, name, discipline.needsReview()));
            String ownKey = TextNormalizer.abbreviationKey(discipline.shortName());
            for (String variant : discipline.variants()) {
                String variantKey = TextNormalizer.abbreviationKey(variant);
                if (!variantKey.equals(ownKey) && aliasKeys.add("D:" + variantKey)) {
                    aliases.add(new CoreImportPayloads.Alias("DISCIPLINE", variant, shortName));
                }
            }
        }
        List<CoreImportPayloads.Room> rooms = new ArrayList<>();
        for (TimetableDraft.Room room : draft.rooms()) {
            if (keys.isNew(EntityType.ROOM, room.name())) {
                RoomType type = room.type();
                rooms.add(new CoreImportPayloads.Room(keys.key(EntityType.ROOM, room.name(), "name"), type, room.maxParallel(),
                        null));
            }
        }
        List<CoreImportPayloads.Group> groups = new ArrayList<>();
        Map<String, CoreImportPayloads.Specialty> specialties = new LinkedHashMap<>();
        for (TimetableDraft.Group group : draft.groups()) {
            if (!keys.isNew(EntityType.GROUP, group.code())) {
                continue;
            }
            groups.add(new CoreImportPayloads.Group(keys.key(EntityType.GROUP, group.code(), "code"), group.course(),
                    group.specialtyLetters(), group.number(), group.shift(),
                    keys.key(EntityType.TEACHER, group.curator(), "shortName"),
                    keys.key(EntityType.ROOM, group.homeRoom(), "name")));
            // назви спеціальності у файлі немає — лише літери; core створює або лишає наявну
            specialties.putIfAbsent(group.specialtyLetters(),
                    new CoreImportPayloads.Specialty(group.specialtyLetters(), null, null, null));
        }
        for (MappingDecision decision : decisions) {
            if (decision.addAlias() && decision.mapsToExisting() && decision.type().supportsAliases()) {
                String target = keys.key(decision.type(), decision.raw(), "shortName");
                String prefix = decision.type() == EntityType.TEACHER ? "T:" : "D:";
                boolean teacher = decision.type() == EntityType.TEACHER;
                String key = teacher ? TextNormalizer.key(decision.raw()) : TextNormalizer.abbreviationKey(decision.raw());
                String targetKey = teacher ? TextNormalizer.key(target) : TextNormalizer.abbreviationKey(target);
                // псевдонім, що збігається з короткою назвою, нічого не дає
                if (!key.equals(targetKey) && aliasKeys.add(prefix + key)) {
                    aliases.add(new CoreImportPayloads.Alias(decision.type().name(), decision.raw(), target));
                }
            }
        }
        return new CoreImportPayloads.CatalogRequest(List.of(), List.copyOf(specialties.values()), teachers, disciplines,
                rooms, groups, aliases);
    }

    private static String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }
}
