package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictReport;
import ua.vtc.rozklad.core.timetable.internal.conflict.LessonDay;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotLesson;

/**
 * Editing the lessons of a draft version. Conflicts are allowed in a draft and returned for the
 * touched slots; only a move refuses to create a hard conflict (409, DnD semantics of CONTRACTS.md).
 */
@Service
@Transactional
public class TemplateLessonService {

    private final TemplateLessonRepository lessons;
    private final VersionLookup versions;
    private final LessonReferenceValidator references;
    private final VersionAnalysis analysis;
    private final TemplateLessonMapper mapper;

    public TemplateLessonService(TemplateLessonRepository lessons, VersionLookup versions,
                                 LessonReferenceValidator references, VersionAnalysis analysis,
                                 TemplateLessonMapper mapper) {
        this.lessons = lessons;
        this.versions = versions;
        this.references = references;
        this.analysis = analysis;
        this.mapper = mapper;
    }

    /** Lessons of a version filtered by group, teacher and room (all optional). */
    @Transactional(readOnly = true)
    public List<TemplateLessonDto> list(UUID versionId, UUID groupId, UUID teacherId, UUID roomId) {
        versions.require(versionId);
        List<TemplateLesson> found;
        if (groupId != null) {
            found = lessons.findAllByVersionIdAndGroupId(versionId, groupId);
        } else if (teacherId != null) {
            found = lessons.findAllByVersionIdAndTeacherId(versionId, teacherId);
        } else if (roomId != null) {
            found = lessons.findAllByVersionIdAndRoomId(versionId, roomId);
        } else {
            found = lessons.findAllByVersionId(versionId);
        }
        return mapper.toDtos(found.stream()
                .filter(lesson -> teacherId == null || teacherId.equals(lesson.getTeacherId()))
                .filter(lesson -> roomId == null || roomId.equals(lesson.getRoomId()))
                .toList());
    }

    public TemplateLessonMutationResponse create(UUID versionId, TemplateLessonCommand command) {
        TimetableVersion version = versions.requireDraftForUpdate(versionId);
        references.validate(command, version.getSemesterId());
        TemplateLesson lesson = new TemplateLesson(versionId, command.weekday(), command.pair(), command.parity(),
                command.groupId(), command.subgroup(), command.disciplineId());
        apply(lesson, command);
        lessons.save(lesson);
        return response(version, lesson, Set.of(new Slot(command.weekday(), command.pair())));
    }

    public TemplateLessonMutationResponse update(UUID versionId, UUID lessonId, TemplateLessonCommand command) {
        TimetableVersion version = versions.requireDraftForUpdate(versionId);
        TemplateLesson lesson = requireLesson(versionId, lessonId);
        references.validate(command, version.getSemesterId());
        Slot before = Slot.of(lesson);
        lesson.moveTo(command.weekday(), command.pair(), command.parity());
        lesson.setGroupId(command.groupId());
        lesson.setSubgroupNumber(command.subgroup());
        lesson.setDisciplineId(command.disciplineId());
        apply(lesson, command);
        return response(version, lesson, Set.of(before, Slot.of(lesson)));
    }

    public TemplateLessonMutationResponse delete(UUID versionId, UUID lessonId) {
        TimetableVersion version = versions.requireDraftForUpdate(versionId);
        TemplateLesson lesson = requireLesson(versionId, lessonId);
        Slot slot = Slot.of(lesson);
        lessons.delete(lesson);
        lessons.flush();
        ConflictReport report = analysis.conflicts(version.getSemesterId(),
                analysis.slotLessons(lessons.findAllByVersionId(versionId)));
        return new TemplateLessonMutationResponse(null, touched(report, lessonId, Set.of(slot)));
    }

    /** Drag & drop preview: what the move would give, nothing is saved. 409 when it creates hard conflicts. */
    @Transactional(readOnly = true)
    public TemplateLessonMutationResponse previewMove(UUID versionId, UUID lessonId, MoveCommand target) {
        TimetableVersion version = versions.requireDraft(versionId);
        TemplateLesson lesson = requireLesson(versionId, lessonId);
        references.validateRoom(target.roomId());
        ConflictReport report = reportAfterMove(version, lesson, target);
        UUID roomId = target.roomId() != null ? target.roomId() : lesson.getRoomId();
        return new TemplateLessonMutationResponse(
                mapper.preview(lesson, target.weekday(), target.pair(), target.parity(), roomId),
                touched(report, lessonId, Set.of(Slot.of(lesson), new Slot(target.weekday(), target.pair()))));
    }

    /** Moves the lesson unless the move gives it a hard conflict (409 with {@code conflicts[]}). */
    public TemplateLessonMutationResponse move(UUID versionId, UUID lessonId, MoveCommand target) {
        TimetableVersion version = versions.requireDraftForUpdate(versionId);
        TemplateLesson lesson = requireLesson(versionId, lessonId);
        references.validateRoom(target.roomId());
        Slot before = Slot.of(lesson);
        ConflictReport report = reportAfterMove(version, lesson, target);
        lesson.moveTo(target.weekday(), target.pair(), target.parity());
        if (target.roomId() != null) {
            lesson.setRoomId(target.roomId());
        }
        return new TemplateLessonMutationResponse(mapper.toDto(lesson),
                touched(report, lessonId, Set.of(before, Slot.of(lesson))));
    }

    private ConflictReport reportAfterMove(TimetableVersion version, TemplateLesson lesson, MoveCommand target) {
        UUID roomId = target.roomId() != null ? target.roomId() : lesson.getRoomId();
        List<SlotLesson> moved = new ArrayList<>();
        for (SlotLesson slotLesson : analysis.slotLessons(lessons.findAllByVersionId(version.getId()))) {
            moved.add(slotLesson.id().equals(lesson.getId())
                    ? slotLesson.movedTo(LessonDay.ofWeekday(target.weekday()), target.pair(), target.parity(), roomId)
                    : slotLesson);
        }
        ConflictReport report = analysis.conflicts(version.getSemesterId(), moved);
        List<ConflictDto> ownHard = report.involving(List.of(lesson.getId())).hard();
        if (!ownHard.isEmpty()) {
            throw new ConflictException("Перенесення створює конфлікти в розкладі", ownHard);
        }
        return report;
    }

    private TemplateLessonMutationResponse response(TimetableVersion version, TemplateLesson lesson,
                                                    Set<Slot> touchedSlots) {
        lessons.flush();
        ConflictReport report = analysis.conflicts(version.getSemesterId(),
                analysis.slotLessons(lessons.findAllByVersionId(version.getId())));
        return new TemplateLessonMutationResponse(mapper.toDto(lesson), touched(report, lesson.getId(), touchedSlots));
    }

    /** Conflicts of the lesson itself and of every touched slot (weekday × pair). */
    private static List<ConflictDto> touched(ConflictReport report, UUID lessonId, Set<Slot> slots) {
        return report.filter(conflict -> conflict.lessonIds().contains(lessonId)
                        || (conflict.weekday() != null && slots.contains(new Slot(conflict.weekday(), conflict.pair()))))
                .all();
    }

    private TemplateLesson requireLesson(UUID versionId, UUID lessonId) {
        return lessons.findById(lessonId)
                .filter(lesson -> lesson.getVersionId().equals(versionId))
                .orElseThrow(() -> NotFoundException.of("Заняття", lessonId));
    }

    private static void apply(TemplateLesson lesson, TemplateLessonCommand command) {
        lesson.setTeacherId(command.teacherId());
        lesson.setRoomId(command.roomId());
        lesson.setLessonKind(command.lessonKind());
        lesson.setLoadItemId(command.loadItemId());
        lesson.setPinned(command.pinned());
        lesson.setOnline(command.online());
        lesson.setStreamId(command.streamId());
    }

    private record Slot(int weekday, int pair) {

        static Slot of(TemplateLesson lesson) {
            return new Slot(lesson.getWeekday(), lesson.getPairNumber());
        }
    }
}
