package ua.vtc.rozklad.core.substitution.internal.absence;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** Absences for the dispatcher (CONTRACTS §2 «Substitutions»). */
@RestController
@RequestMapping("/api/admin/absences")
class AdminAbsenceController {

    /** Default window of the list when {@code to} is omitted. */
    static final int DEFAULT_DAYS = 31;

    private final AbsenceService absences;
    private final AffectedLessons affectedLessons;
    private final Clock clock;

    AdminAbsenceController(AbsenceService absences, AffectedLessons affectedLessons, Clock clock) {
        this.absences = absences;
        this.affectedLessons = affectedLessons;
        this.clock = clock;
    }

    /** Active absences overlapping {@code [from, to]}; by default from today for 31 days. */
    @GetMapping
    List<AbsenceDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) UUID teacherId) {
        LocalDate start = from != null ? from : LocalDate.now(clock);
        LocalDate end = to != null ? to : start.plusDays(DEFAULT_DAYS);
        if (end.isAfter(start.plusDays(ValidDateRange.MAX_DAYS))) {
            throw new BusinessRuleException("INVALID_PERIOD", "Період вибірки не може перевищувати рік.");
        }
        return absences.list(start, end, teacherId);
    }

    @GetMapping("/{id}")
    AbsenceDto get(@PathVariable UUID id) {
        return absences.get(id);
    }

    @PostMapping
    ResponseEntity<AbsenceDto> create(@Valid @RequestBody AbsenceRequests.Admin request, CurrentUser user) {
        AbsenceDto created = absences.report(request.teacherId(), request.data(), AbsenceSource.ADMIN,
                user.username());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** The teacher of an absence cannot change; {@code teacherId} must stay the same (422). */
    @PutMapping("/{id}")
    AbsenceDto update(@PathVariable UUID id, @Valid @RequestBody AbsenceRequests.Admin request, CurrentUser user) {
        return absences.update(id, request.teacherId(), request.data(), request.version(), user.username());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@PathVariable UUID id, CurrentUser user) {
        absences.cancel(id, user.username());
        return ResponseEntity.noContent().build();
    }

    /** Lessons of the absent teacher with the current decision about each of them. */
    @GetMapping("/{id}/affected")
    List<AffectedLessonDto> affected(@PathVariable UUID id) {
        return affectedLessons.of(id);
    }
}
