package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionSource;
import ua.vtc.rozklad.core.timetable.internal.diff.VersionDiffCalculator;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/** Versions of the base timetable: list with quality figures, create / copy, delete drafts, analysis, diff. */
@Service
@Transactional
public class TimetableVersionService {

    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository lessons;
    private final SemesterRepository semesters;
    private final VersionLookup lookup;
    private final VersionAnalysis analysis;
    private final VersionDiffCalculator diffCalculator;
    private final TemplateLessonMapper mapper;

    public TimetableVersionService(TimetableVersionRepository versions, TemplateLessonRepository lessons,
                                   SemesterRepository semesters, VersionLookup lookup, VersionAnalysis analysis,
                                   VersionDiffCalculator diffCalculator, TemplateLessonMapper mapper) {
        this.versions = versions;
        this.lessons = lessons;
        this.semesters = semesters;
        this.lookup = lookup;
        this.analysis = analysis;
        this.diffCalculator = diffCalculator;
        this.mapper = mapper;
    }

    /** Versions of the semester (the active one when {@code semesterId} is null), newest first. */
    @Transactional(readOnly = true)
    public List<TimetableVersionDto> list(UUID semesterId) {
        UUID semester = semesterId != null ? semesterId
                : semesters.findByActiveTrue().map(Semester::getId).orElse(null);
        if (semester == null) {
            return List.of();
        }
        List<TimetableVersion> found = versions.findAllBySemesterIdOrderByCreatedAtDesc(semester);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<TemplateLesson>> lessonsByVersion = new LinkedHashMap<>();
        found.forEach(version -> lessonsByVersion.put(version.getId(), new ArrayList<>()));
        lessons.findAllByVersionIdIn(lessonsByVersion.keySet())
                .forEach(lesson -> lessonsByVersion.get(lesson.getVersionId()).add(lesson));
        Map<UUID, VersionAnalysis.Result> results = analysis.analyzeAll(semester, lessonsByVersion);
        return found.stream()
                .map(version -> toDto(version, lessonsByVersion.get(version.getId()).size(),
                        results.get(version.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TimetableVersionDto get(UUID versionId) {
        TimetableVersion version = lookup.require(versionId);
        List<TemplateLesson> versionLessons = lessons.findAllByVersionId(versionId);
        return toDto(version, versionLessons.size(), analysis.analyze(version.getSemesterId(), versionLessons));
    }

    /** New draft, empty or a copy of another version's lessons (load items are kept within one semester). */
    public TimetableVersionDto create(UUID semesterId, String name, UUID copyFromVersionId) {
        Semester semester = semesters.findById(semesterId).orElseThrow(() -> new BusinessRuleException(
                LessonReferenceValidator.REFERENCE_NOT_FOUND, NotFoundException.of("Семестр", semesterId).getMessage()));
        if (copyFromVersionId == null) {
            TimetableVersion draft = versions.save(new TimetableVersion(semester.getId(), name.strip(),
                    VersionSource.MANUAL));
            return toDto(draft, 0, analysis.analyze(semester.getId(), List.of()));
        }
        TimetableVersion source = versions.findById(copyFromVersionId).orElseThrow(() -> new BusinessRuleException(
                LessonReferenceValidator.REFERENCE_NOT_FOUND,
                NotFoundException.of("Версію розкладу", copyFromVersionId).getMessage()));
        TimetableVersion draft = versions.save(new TimetableVersion(semester.getId(), name.strip(), VersionSource.COPY));
        boolean sameSemester = source.getSemesterId().equals(semester.getId());
        List<TemplateLesson> copies = lessons.findAllByVersionId(source.getId()).stream()
                .map(lesson -> copy(lesson, draft.getId(), sameSemester))
                .toList();
        lessons.saveAll(copies);
        return toDto(draft, copies.size(), analysis.analyze(semester.getId(), copies));
    }

    /** Only a draft can be deleted (published and archived versions are the history of the schedule). */
    public void delete(UUID versionId) {
        TimetableVersion version = lookup.require(versionId);
        if (!version.isDraft()) {
            throw new ConflictException("Видалити можна лише чернетку: версія «%s» %s."
                    .formatted(version.getName(), VersionLookup.statusLabel(version.getStatus())));
        }
        lessons.deleteAllInVersion(versionId);
        versions.delete(version);
    }

    @Transactional(readOnly = true)
    public VersionConflictsDto conflicts(UUID versionId) {
        TimetableVersion version = lookup.require(versionId);
        VersionAnalysis.Result result = analysis.analyze(version.getSemesterId(), lessons.findAllByVersionId(versionId));
        return new VersionConflictsDto(result.conflicts().hard(), result.conflicts().soft());
    }

    @Transactional(readOnly = true)
    public MetricsDto metrics(UUID versionId) {
        TimetableVersion version = lookup.require(versionId);
        return analysis.analyze(version.getSemesterId(), lessons.findAllByVersionId(versionId)).metrics();
    }

    /** What changes from version {@code fromId} to version {@code toId}. */
    @Transactional(readOnly = true)
    public VersionDiffDto diff(UUID fromId, UUID toId) {
        lookup.require(fromId);
        lookup.require(toId);
        List<TemplateLesson> from = lessons.findAllByVersionId(fromId);
        List<TemplateLesson> to = lessons.findAllByVersionId(toId);
        VersionDiffCalculator.Diff diff = diffCalculator.diff(from, to);
        Function<TemplateLesson, TemplateLessonDto> dto = mapper.converter(
                Stream.concat(from.stream(), to.stream()).toList());
        return new VersionDiffDto(map(diff.added(), dto), map(diff.removed(), dto),
                diff.moved().stream()
                        .map(moved -> new VersionDiffDto.MovedLesson(dto.apply(moved.from()), dto.apply(moved.to())))
                        .toList());
    }

    private static List<TemplateLessonDto> map(Collection<TemplateLesson> lessons,
                                               Function<TemplateLesson, TemplateLessonDto> dto) {
        return lessons.stream().map(dto).collect(Collectors.toList());
    }

    private static TemplateLesson copy(TemplateLesson source, UUID versionId, boolean keepLoadItem) {
        TemplateLesson copy = new TemplateLesson(versionId, source.getWeekday(), source.getPairNumber(),
                source.getParity(), source.getGroupId(), source.getSubgroupNumber(), source.getDisciplineId());
        copy.setTeacherId(source.getTeacherId());
        copy.setRoomId(source.getRoomId());
        copy.setLessonKind(source.getLessonKind());
        copy.setLoadItemId(keepLoadItem ? source.getLoadItemId() : null);
        copy.setStreamId(source.getStreamId());
        copy.setPinned(source.isPinned());
        copy.setOnline(source.isOnline());
        return copy;
    }

    static TimetableVersionDto toDto(TimetableVersion version, int lessonCount, VersionAnalysis.Result result) {
        return new TimetableVersionDto(version.getId(), version.getSemesterId(), version.getName(),
                version.getStatus(), version.getSource(), version.getEffectiveFrom(), version.getPublishedAt(),
                version.getPublishedBy(), version.getGenerationJobId(), version.getNotes(), version.getCreatedAt(),
                lessonCount, result.conflicts().hard().size(), result.conflicts().soft().size(), result.metrics());
    }
}
