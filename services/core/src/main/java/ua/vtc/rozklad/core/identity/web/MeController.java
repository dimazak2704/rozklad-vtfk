package ua.vtc.rozklad.core.identity.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.identity.UserProfile;

/** {@code GET /api/me} (TEACHER or ADMIN): who am I and which teacher is my account. */
@RestController
class MeController {

    private final CurrentUserService currentUser;

    MeController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me")
    UserProfile me() {
        return currentUser.profile();
    }
}
