package ua.vtc.rozklad.core.calendar.web;

import jakarta.validation.Valid;
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
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarPeriodService;
import ua.vtc.rozklad.core.shared.web.Created;

/** {@code /api/admin/calendar-periods?semesterId=}: CRUD of holidays, practice, study modes, exam sessions. */
@RestController
@RequestMapping("/api/admin/calendar-periods")
class AdminCalendarPeriodController {

    private final CalendarPeriodService periods;

    AdminCalendarPeriodController(CalendarPeriodService periods) {
        this.periods = periods;
    }

    @GetMapping
    List<CalendarPeriodDto> list(@RequestParam(required = false) UUID semesterId) {
        return periods.list(semesterId);
    }

    @GetMapping("/{id}")
    CalendarPeriodDto get(@PathVariable UUID id) {
        return periods.get(id);
    }

    @PostMapping
    ResponseEntity<CalendarPeriodDto> create(@Valid @RequestBody CalendarPeriodRequest request) {
        CalendarPeriodDto created = periods.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    CalendarPeriodDto update(@PathVariable UUID id, @Valid @RequestBody CalendarPeriodRequest request) {
        return periods.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        periods.delete(id);
        return ResponseEntity.noContent().build();
    }
}
