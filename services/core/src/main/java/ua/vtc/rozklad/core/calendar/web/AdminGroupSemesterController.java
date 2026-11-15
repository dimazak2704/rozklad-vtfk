package ua.vtc.rozklad.core.calendar.web;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.GroupSemesterService;
import ua.vtc.rozklad.core.calendar.internal.GroupSemesterService.GroupSemesterDto;

/** {@code GET /api/admin/group-semesters?semesterId=}: theory weeks and exams per group from the academic calendar. */
@RestController
class AdminGroupSemesterController {

    private final GroupSemesterService groupSemesters;

    AdminGroupSemesterController(GroupSemesterService groupSemesters) {
        this.groupSemesters = groupSemesters;
    }

    @GetMapping("/api/admin/group-semesters")
    List<GroupSemesterDto> list(@RequestParam UUID semesterId) {
        return groupSemesters.list(semesterId);
    }
}
