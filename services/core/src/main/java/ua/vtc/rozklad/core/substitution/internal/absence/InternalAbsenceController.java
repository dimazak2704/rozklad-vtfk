package ua.vtc.rozklad.core.substitution.internal.absence;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** Absence reported by a teacher in the Telegram bot (CONTRACTS §4, role SERVICE). */
@RestController
@RequestMapping("/api/internal/absences")
class InternalAbsenceController {

    private final AbsenceService absences;

    InternalAbsenceController(AbsenceService absences) {
        this.absences = absences;
    }

    @PostMapping
    ResponseEntity<AbsenceDto> report(@Valid @RequestBody AbsenceRequests.Bot request, CurrentUser user) {
        if (request.source() != null && request.source() != AbsenceSource.BOT) {
            throw new BusinessRuleException("ABSENCE_SOURCE", "Через внутрішній API відсутність надходить лише з бота.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(absences.report(request.teacherId(), request.data(), AbsenceSource.BOT, user.username()));
    }
}
