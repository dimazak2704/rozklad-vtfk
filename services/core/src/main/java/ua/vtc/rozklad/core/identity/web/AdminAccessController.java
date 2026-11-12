package ua.vtc.rozklad.core.identity.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessDto;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessStatus;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.GrantRequest;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.UpdateRequest;
import ua.vtc.rozklad.core.identity.internal.AccessService;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.PasswordLinkDto;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.web.Created;

/**
 * «Доступи» (ADMIN, DESIGN §1.5): who may sign in and with which role. Errors: 400 validation, 404 unknown
 * account, 409 {@code code} = {@code EMAIL_TAKEN} | {@code TEACHER_ALREADY_LINKED} | {@code LAST_ADMIN} |
 * {@code ACCOUNT_REVOKED}, 422 {@code code} = {@code TEACHER_INACTIVE} | {@code TEACHER_ROLE_REQUIRED} |
 * {@code SELF_REVOKE}, 503 sign-in service down.
 */
@RestController
@RequestMapping("/api/admin/access")
class AdminAccessController {

    private final AccessService access;

    AdminAccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping
    List<AccessDto> list(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String role,
                         @RequestParam(required = false) AccessStatus status) {
        return access.list(q, role, status);
    }

    @PostMapping
    ResponseEntity<AccessDto> grant(@Valid @RequestBody GrantRequest request, CurrentUser user) {
        AccessDto granted = access.grant(request, user.username());
        return Created.at(granted.id(), granted);
    }

    @PatchMapping("/{accountId}")
    AccessDto update(@PathVariable UUID accountId, @Valid @RequestBody UpdateRequest request, CurrentUser user) {
        return access.update(accountId, request, user.username());
    }

    @PostMapping("/{accountId}/revoke")
    AccessDto revoke(@PathVariable UUID accountId, CurrentUser user) {
        return access.revoke(accountId, user);
    }

    @PostMapping("/{accountId}/restore")
    AccessDto restore(@PathVariable UUID accountId, CurrentUser user) {
        return access.restore(accountId, user.username());
    }

    @DeleteMapping("/{accountId}/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unbindGoogle(@PathVariable UUID accountId, CurrentUser user) {
        access.unbindGoogle(accountId, user.username());
    }

    @PostMapping("/{accountId}/password-link")
    @ResponseStatus(HttpStatus.CREATED)
    PasswordLinkDto passwordLink(@PathVariable UUID accountId, CurrentUser user) {
        return access.passwordLink(accountId, user.username());
    }
}
