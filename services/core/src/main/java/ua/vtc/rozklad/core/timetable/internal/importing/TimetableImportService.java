package ua.vtc.rozklad.core.timetable.internal.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.catalog.DisciplineAliasRepository;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionSource;
import ua.vtc.rozklad.core.timetable.internal.importing.CatalogNameIndex.Resolution;
import ua.vtc.rozklad.core.timetable.internal.version.LessonReferenceValidator;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;

/**
 * Applies an imported timetable as a new draft version (source IMPORT). Every name must resolve to
 * exactly one catalog entry through names or aliases; otherwise nothing is saved and the importer
 * gets the list of unresolved names (422). Conflicts of the draft are reported, not refused.
 */
@Service
public class TimetableImportService {

    private final SemesterRepository semesters;
    private final StudentGroupRepository groups;
    private final TeacherRepository teachers;
    private final TeacherAliasRepository teacherAliases;
    private final DisciplineRepository disciplines;
    private final DisciplineAliasRepository disciplineAliases;
    private final RoomRepository rooms;
    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository lessons;
    private final VersionAnalysis analysis;

    public TimetableImportService(SemesterRepository semesters, StudentGroupRepository groups,
                                  TeacherRepository teachers, TeacherAliasRepository teacherAliases,
                                  DisciplineRepository disciplines, DisciplineAliasRepository disciplineAliases,
                                  RoomRepository rooms, TimetableVersionRepository versions,
                                  TemplateLessonRepository lessons, VersionAnalysis analysis) {
        this.semesters = semesters;
        this.groups = groups;
        this.teachers = teachers;
        this.teacherAliases = teacherAliases;
        this.disciplines = disciplines;
        this.disciplineAliases = disciplineAliases;
        this.rooms = rooms;
        this.versions = versions;
        this.lessons = lessons;
        this.analysis = analysis;
    }

    @Transactional
    public ImportTimetableResult importTimetable(ImportTimetableCommand command) {
        return importTimetable(command, null);
    }

    /**
     * With an {@code idempotencyKey} (the importer sends {@code import-job:<id>:timetable}) a repeated
     * call returns the draft created by the first one, so a retry after a lost response does not
     * leave a duplicate version; the same key with a different request is a 409. The key lives as
     * long as the draft: after the draft is deleted the key may create a new one.
     */
    @Transactional
    public ImportTimetableResult importTimetable(ImportTimetableCommand command, String idempotencyKey) {
        String requestHash = null;
        if (idempotencyKey != null) {
            versions.lockImportKey(idempotencyKey);
            requestHash = ImportRequestHash.of(command);
            Optional<TimetableVersion> earlier = versions.findByImportKey(idempotencyKey);
            if (earlier.isPresent()) {
                return repeat(earlier.get(), requestHash);
            }
        }
        if (!semesters.existsById(command.semesterId())) {
            throw new BusinessRuleException(LessonReferenceValidator.REFERENCE_NOT_FOUND,
                    NotFoundException.of("Семестр", command.semesterId()).getMessage());
        }
        CatalogNameIndex index = CatalogNameIndex.build(groups.findAll(), teachers.findAll(), teacherAliases.findAll(),
                disciplines.findAll(), disciplineAliases.findAll(), rooms.findAll());
        Problems problems = new Problems();
        List<ResolvedLesson> resolved = new ArrayList<>();
        for (int i = 0; i < command.lessons().size(); i++) {
            ImportTimetableCommand.Lesson lesson = command.lessons().get(i);
            String path = "lessons[" + i + "].";
            UUID groupId = problems.require(index.group(lesson.groupCode()), path + "groupCode",
                    lesson.groupCode(), "Групу", "групам");
            UUID disciplineId = problems.require(index.discipline(lesson.disciplineShortName()),
                    path + "disciplineShortName", lesson.disciplineShortName(), "Дисципліну", "дисциплінам");
            UUID teacherId = isBlank(lesson.teacherShortName()) ? null
                    : problems.require(index.teacher(lesson.teacherShortName()), path + "teacherShortName",
                    lesson.teacherShortName(), "Викладача", "викладачам");
            UUID roomId = isBlank(lesson.roomName()) ? null
                    : problems.require(index.room(lesson.roomName()), path + "roomName", lesson.roomName(),
                    "Аудиторію", "аудиторіям");
            resolved.add(new ResolvedLesson(lesson, groupId, disciplineId, teacherId, roomId));
        }
        if (!problems.isEmpty()) {
            throw new UnresolvedImportReferencesException(problems.errors());
        }

        TimetableVersion draft = new TimetableVersion(command.semesterId(), command.name().strip(), VersionSource.IMPORT);
        if (idempotencyKey != null) {
            draft.markImported(idempotencyKey, requestHash);
        }
        TimetableVersion version = versions.save(draft);
        List<TemplateLesson> created = resolved.stream().map(lesson -> lesson.toTemplate(version.getId())).toList();
        lessons.saveAll(created);
        VersionAnalysis.Result result = analysis.analyze(command.semesterId(), created);
        return new ImportTimetableResult(version.getId(), version.getName(), created.size(),
                result.conflicts().hard().size(), result.conflicts().soft().size());
    }

    private ImportTimetableResult repeat(TimetableVersion version, String requestHash) {
        if (!requestHash.equals(version.getImportRequestHash())) {
            throw new ConflictException("Ключ Idempotency-Key уже використано для іншого імпорту розкладу "
                    + "(чернетка «%s»).".formatted(version.getName()));
        }
        List<TemplateLesson> existing = lessons.findAllByVersionId(version.getId());
        VersionAnalysis.Result result = analysis.analyze(version.getSemesterId(), existing);
        return new ImportTimetableResult(version.getId(), version.getName(), existing.size(),
                result.conflicts().hard().size(), result.conflicts().soft().size());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ResolvedLesson(ImportTimetableCommand.Lesson source, UUID groupId, UUID disciplineId,
                                  UUID teacherId, UUID roomId) {

        TemplateLesson toTemplate(UUID versionId) {
            TemplateLesson lesson = new TemplateLesson(versionId, source.weekday(), source.pair(), source.parity(),
                    groupId, source.subgroup(), disciplineId);
            lesson.setTeacherId(teacherId);
            lesson.setRoomId(roomId);
            lesson.setOnline(source.online());
            return lesson;
        }
    }

    /** Unresolved names, one entry per field and spelling (with the first row where it occurs). */
    private static final class Problems {

        private final Map<String, Problem> byName = new LinkedHashMap<>();

        UUID require(Resolution resolution, String field, String raw, String subject, String pluralDative) {
            return switch (resolution) {
                case Resolution.Found found -> found.id();
                case Resolution.Missing missing -> {
                    add(field, raw, "%s «%s» не знайдено в каталозі".formatted(subject, raw));
                    yield null;
                }
                case Resolution.Ambiguous ambiguous -> {
                    add(field, raw, "Назва «%s» підходить кільком %s: уточніть її або додайте псевдонім"
                            .formatted(raw, pluralDative));
                    yield null;
                }
            };
        }

        private void add(String field, String raw, String message) {
            String attribute = field.substring(field.indexOf('.') + 1);
            byName.computeIfAbsent(attribute + "\u0000" + raw, key -> new Problem(field, message)).occurrences++;
        }

        boolean isEmpty() {
            return byName.isEmpty();
        }

        List<FieldErrorDto> errors() {
            return byName.values().stream()
                    .map(problem -> new FieldErrorDto(problem.field, problem.occurrences > 1
                            ? problem.message + " (рядків: " + problem.occurrences + ")"
                            : problem.message))
                    .toList();
        }

        private static final class Problem {

            private final String field;
            private final String message;
            private int occurrences;

            private Problem(String field, String message) {
                this.field = field;
                this.message = message;
            }
        }
    }
}
