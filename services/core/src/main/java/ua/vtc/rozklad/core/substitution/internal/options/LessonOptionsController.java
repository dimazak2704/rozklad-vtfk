package ua.vtc.rozklad.core.substitution.internal.options;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Ranked ways to handle a lesson of an absent teacher (CONTRACTS §2). */
@RestController
class LessonOptionsController {

    private final LessonOptions options;

    LessonOptionsController(LessonOptions options) {
        this.options = options;
    }

    @GetMapping("/api/admin/lessons/{lessonId}/options")
    OptionDto.LessonOptions options(@PathVariable UUID lessonId) {
        return options.of(lessonId);
    }
}
