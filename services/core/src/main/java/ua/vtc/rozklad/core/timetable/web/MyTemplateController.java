package ua.vtc.rozklad.core.timetable.web;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonDto;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;

/**
 * {@code GET /api/me/template?semesterId}: the signed-in teacher's weekly slots in the semester's published version
 * (the base timetable, without one-off changes and calendar periods). The wishes grid shows them as «мої пари»;
 * an empty list when the semester has no published version yet; 403 for an account without a linked teacher.
 */
@RestController
class MyTemplateController {

    private final TimetableVersionRepository versions;
    private final TemplateLessonService lessons;
    private final CurrentUserService currentUser;

    MyTemplateController(TimetableVersionRepository versions, TemplateLessonService lessons,
                         CurrentUserService currentUser) {
        this.versions = versions;
        this.lessons = lessons;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me/template")
    List<TemplateLessonDto> template(@RequestParam UUID semesterId) {
        UUID teacherId = currentUser.requireTeacherId();
        return versions.findPublished(semesterId)
                .map(version -> lessons.list(version.getId(), null, teacherId, null))
                .orElse(List.of());
    }
}
