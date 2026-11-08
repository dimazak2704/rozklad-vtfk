package ua.vtc.rozklad.core.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService.TeacherAccountDto;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/**
 * Account of a teacher (ADMIN): {@code GET} shows the link, {@code PUT {username}} links an existing
 * Keycloak account (200; 404 teacher, 422 no such / disabled account, 409 taken, 503 Keycloak down),
 * {@code DELETE} removes the link (204). Invites remain the way to create new accounts.
 */
@RestController
@RequestMapping("/api/admin/teachers/{teacherId}/account")
class AdminTeacherAccountController {

    private final TeacherAccountService accounts;

    AdminTeacherAccountController(TeacherAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    TeacherAccountDto get(@PathVariable UUID teacherId) {
        return accounts.get(teacherId);
    }

    @PutMapping
    TeacherAccountDto link(@PathVariable UUID teacherId, @Valid @RequestBody LinkAccountRequest request,
                           CurrentUser user) {
        return accounts.link(teacherId, request.username(), user.username());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(@PathVariable UUID teacherId, CurrentUser user) {
        accounts.unlink(teacherId, user.username());
    }

    record LinkAccountRequest(
            @NotBlank(message = "Вкажіть ім'я користувача") @Size(max = 100) String username) {
    }
}
