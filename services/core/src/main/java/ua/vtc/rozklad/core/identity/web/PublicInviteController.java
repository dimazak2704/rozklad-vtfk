package ua.vtc.rozklad.core.identity.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.AcceptInviteRequest;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.AcceptedInviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteInfoDto;
import ua.vtc.rozklad.core.identity.internal.InviteService;

/**
 * Teacher registration page ({@code /invite/:token} in the SPA): {@code GET} shows whom the
 * invite is for; {@code POST /accept} creates the account (201), 409 username taken, 410 expired
 * or used.
 */
@RestController
@RequestMapping("/api/public/invites/{token}")
class PublicInviteController {

    private final InviteService invites;

    PublicInviteController(InviteService invites) {
        this.invites = invites;
    }

    @GetMapping
    InviteInfoDto inspect(@PathVariable String token) {
        return invites.inspect(token);
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    AcceptedInviteDto accept(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest request) {
        return invites.accept(token, request.username(), request.password());
    }
}
