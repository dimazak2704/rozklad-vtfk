package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ranked ways to handle a lesson of an absent teacher, the manual teacher list and the day plan (CONTRACTS §2). */
@RestController
class LessonOptionsController {

    private final LessonOptions options;
    private final TeacherAvailability availability;

    LessonOptionsController(LessonOptions options, TeacherAvailability availability) {
        this.options = options;
        this.availability = availability;
    }

    @GetMapping("/api/admin/lessons/{lessonId}/options")
    OptionDto.LessonOptions options(@PathVariable UUID lessonId) {
        return options.of(lessonId);
    }

    /** Every active teacher's state at the lesson's pair, free first (manual «Інший викладач…»). */
    @GetMapping("/api/admin/lessons/{lessonId}/teacher-availability")
    List<TeacherAvailability.Item> availability(@PathVariable UUID lessonId,
                                                @RequestParam(required = false) String q) {
        return availability.of(lessonId, q);
    }

    /** Proposal for every undecided lesson of the absence on the date; nothing is created or sent. */
    @GetMapping("/api/admin/absences/{absenceId}/plan")
    DayPlanDto plan(@PathVariable UUID absenceId,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return options.plan(absenceId, date);
    }
}
