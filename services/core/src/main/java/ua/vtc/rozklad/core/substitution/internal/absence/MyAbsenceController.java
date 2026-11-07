package ua.vtc.rozklad.core.substitution.internal.absence;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;

/** The teacher's own absences (CONTRACTS §3): own only; cancelling only before the absence starts. */
@RestController
@RequestMapping("/api/me/absences")
class MyAbsenceController {

    private final AbsenceService absences;
    private final CurrentTeacher currentTeacher;

    MyAbsenceController(AbsenceService absences, CurrentTeacher currentTeacher) {
        this.absences = absences;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    List<AbsenceDto> mine(CurrentUser user) {
        return absences.ofTeacher(currentTeacher.require(user).getId());
    }

    @PostMapping
    ResponseEntity<AbsenceDto> report(@Valid @RequestBody AbsenceRequests.Mine request, CurrentUser user) {
        Teacher teacher = currentTeacher.require(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(absences.report(teacher.getId(), request.data(), AbsenceSource.WEB, user.username()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@PathVariable UUID id, CurrentUser user) {
        absences.cancelOwn(id, currentTeacher.require(user).getId(), user.username());
        return ResponseEntity.noContent().build();
    }
}
