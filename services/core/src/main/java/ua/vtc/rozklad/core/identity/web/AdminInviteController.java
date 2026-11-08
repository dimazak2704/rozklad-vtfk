package ua.vtc.rozklad.core.identity.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.CreateInviteRequest;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteCreatedDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteService;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.web.Created;

/** {@code POST /api/admin/invites {teacherId}} → one-time link; {@code GET /api/admin/invites?teacherId=}. */
@RestController
@RequestMapping("/api/admin/invites")
class AdminInviteController {

    private final InviteService invites;

    AdminInviteController(InviteService invites) {
        this.invites = invites;
    }

    @PostMapping
    ResponseEntity<InviteCreatedDto> create(@Valid @RequestBody CreateInviteRequest request, CurrentUser user) {
        InviteCreatedDto created = invites.create(request.teacherId(), user.username());
        return Created.at(created.id(), created);
    }

    @GetMapping
    List<InviteDto> list(@RequestParam(required = false) UUID teacherId) {
        return invites.list(teacherId);
    }
}
