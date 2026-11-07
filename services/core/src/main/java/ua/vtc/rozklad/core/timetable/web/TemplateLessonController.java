package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonDto;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonMutationResponse;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;

/**
 * Lessons of a draft version (the timetable editor, CONTRACTS.md §2). Every edit answers with the
 * conflicts of the touched slots; a move refuses a hard conflict with 409 ({@code ?dryRun=true}
 * only previews it, for drag & drop targets).
 */
@RestController
@RequestMapping("/api/admin/timetable/versions/{versionId}/lessons")
class TemplateLessonController {

    private final TemplateLessonService lessons;

    TemplateLessonController(TemplateLessonService lessons) {
        this.lessons = lessons;
    }

    @GetMapping
    List<TemplateLessonDto> list(@PathVariable UUID versionId, @RequestParam(required = false) UUID groupId,
                                 @RequestParam(required = false) UUID teacherId,
                                 @RequestParam(required = false) UUID roomId) {
        return lessons.list(versionId, groupId, teacherId, roomId);
    }

    @PostMapping
    ResponseEntity<TemplateLessonMutationResponse> create(@PathVariable UUID versionId,
                                                          @Valid @RequestBody TimetableRequests.TemplateLesson request) {
        TemplateLessonMutationResponse created = lessons.create(versionId, request.toCommand());
        return ResponseEntity.created(URI.create("/api/admin/timetable/versions/%s/lessons/%s"
                .formatted(versionId, created.lesson().id()))).body(created);
    }

    @PutMapping("/{lessonId}")
    TemplateLessonMutationResponse update(@PathVariable UUID versionId, @PathVariable UUID lessonId,
                                          @Valid @RequestBody TimetableRequests.TemplateLesson request) {
        return lessons.update(versionId, lessonId, request.toCommand());
    }

    /** 200 with the conflicts left in the slot (the lesson itself is {@code null}). */
    @DeleteMapping("/{lessonId}")
    TemplateLessonMutationResponse delete(@PathVariable UUID versionId, @PathVariable UUID lessonId) {
        return lessons.delete(versionId, lessonId);
    }

    @PostMapping("/{lessonId}/move")
    TemplateLessonMutationResponse move(@PathVariable UUID versionId, @PathVariable UUID lessonId,
                                        @RequestParam(defaultValue = "false") boolean dryRun,
                                        @Valid @RequestBody TimetableRequests.Move request) {
        return dryRun ? lessons.previewMove(versionId, lessonId, request.toCommand())
                : lessons.move(versionId, lessonId, request.toCommand());
    }
}
