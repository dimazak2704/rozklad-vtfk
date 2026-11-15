package ua.vtc.rozklad.core.identity.web;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.MailRecipientService;
import ua.vtc.rozklad.core.identity.internal.MailRecipientService.MailRecipientDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * {@code GET /api/internal/mail-recipients?teacherId|accountId|role=ADMIN} (SERVICE_NOTIFICATION): the accounts an
 * e-mail would reach. Exactly one parameter; only the role ADMIN can be listed, so the endpoint never hands out
 * every teacher's address at once.
 */
@RestController
class InternalMailRecipientController {

    private final MailRecipientService recipients;

    InternalMailRecipientController(MailRecipientService recipients) {
        this.recipients = recipients;
    }

    @GetMapping("/api/internal/mail-recipients")
    List<MailRecipientDto> find(@RequestParam(required = false) UUID teacherId,
                                @RequestParam(required = false) UUID accountId,
                                @RequestParam(required = false) String role) {
        long given = Stream.of(teacherId, accountId, role).filter(value -> value != null).count();
        if (given != 1) {
            throw FieldValidationException.of("teacherId", "Вкажіть рівно один параметр: teacherId, accountId або role.");
        }
        if (teacherId != null) {
            return recipients.forTeacher(teacherId);
        }
        if (accountId != null) {
            return recipients.forAccount(accountId);
        }
        if (!Roles.ADMIN.equals(role)) {
            throw FieldValidationException.of("role", "Підтримується лише role=ADMIN.");
        }
        return recipients.admins();
    }
}
