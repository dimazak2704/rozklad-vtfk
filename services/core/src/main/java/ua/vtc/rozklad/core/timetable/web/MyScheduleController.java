package ua.vtc.rozklad.core.timetable.web;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;

/**
 * {@code GET /api/me/schedule} (CONTRACTS §3): the signed-in teacher's own schedule, the same view
 * as {@code /api/public/schedule/teacher/{id}}; 403 for an account without a linked teacher.
 */
@RestController
class MyScheduleController {

    private final ScheduleQueries schedules;
    private final CurrentUserService currentUser;
    private final Clock clock;

    MyScheduleController(ScheduleQueries schedules, CurrentUserService currentUser, Clock clock) {
        this.schedules = schedules;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/api/me/schedule")
    ScheduleView schedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ScheduleRange range = ScheduleRange.resolve(from, to, clock);
        return schedules.schedule(ScheduleOwnerType.TEACHER, currentUser.requireTeacherId(), range.from(), range.to());
    }
}
