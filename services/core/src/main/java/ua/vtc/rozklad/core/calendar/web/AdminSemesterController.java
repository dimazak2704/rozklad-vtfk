package ua.vtc.rozklad.core.calendar.web;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterWeekDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.WeekOverrideRequest;
import ua.vtc.rozklad.core.calendar.internal.SemesterService;
import ua.vtc.rozklad.core.shared.web.Created;

/**
 * {@code /api/admin/semesters}: CRUD, {@code POST /{id}/activate}, {@code GET /{id}/weeks} and
 * parity overrides {@code PUT|DELETE /{id}/weeks/{weekStart}} (weekStart = Monday, ISO date).
 */
@RestController
@RequestMapping("/api/admin/semesters")
class AdminSemesterController {

    private final SemesterService semesters;

    AdminSemesterController(SemesterService semesters) {
        this.semesters = semesters;
    }

    @GetMapping
    List<SemesterDto> list() {
        return semesters.list();
    }

    @GetMapping("/{id}")
    SemesterDto get(@PathVariable UUID id) {
        return semesters.get(id);
    }

    @PostMapping
    ResponseEntity<SemesterDto> create(@Valid @RequestBody SemesterRequest request) {
        SemesterDto created = semesters.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    SemesterDto update(@PathVariable UUID id, @Valid @RequestBody SemesterRequest request) {
        return semesters.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        semesters.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    SemesterDto activate(@PathVariable UUID id) {
        return semesters.activate(id);
    }

    @GetMapping("/{id}/weeks")
    List<SemesterWeekDto> weeks(@PathVariable UUID id) {
        return semesters.weeks(id);
    }

    @PutMapping("/{id}/weeks/{weekStart}")
    SemesterWeekDto overrideWeek(@PathVariable UUID id,
                                 @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
                                 @RequestBody WeekOverrideRequest request) {
        return semesters.overrideWeek(id, weekStart, request.parity());
    }

    @DeleteMapping("/{id}/weeks/{weekStart}")
    ResponseEntity<Void> clearOverride(@PathVariable UUID id,
                                       @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        semesters.overrideWeek(id, weekStart, null);
        return ResponseEntity.noContent().build();
    }
}
