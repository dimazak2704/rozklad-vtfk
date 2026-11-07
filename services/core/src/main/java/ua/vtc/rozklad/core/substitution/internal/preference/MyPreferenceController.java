package ua.vtc.rozklad.core.substitution.internal.preference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;

/** The teacher's preference grid (CONTRACTS §3); the active semester when {@code semesterId} is omitted. */
@RestController
@RequestMapping("/api/me/preferences")
class MyPreferenceController {

    /** 5 weekdays × 8 pairs. */
    static final int MAX_SLOTS = 40;

    private final PreferenceService preferences;
    private final CurrentTeacher currentTeacher;

    MyPreferenceController(PreferenceService preferences, CurrentTeacher currentTeacher) {
        this.preferences = preferences;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    List<PreferenceDto> grid(@RequestParam(required = false) UUID semesterId, CurrentUser user) {
        return preferences.grid(currentTeacher.require(user).getId(), semesterId);
    }

    @PutMapping
    List<PreferenceDto> replace(@RequestParam(required = false) UUID semesterId,
                                @RequestBody @NotNull @Size(max = MAX_SLOTS) List<@Valid @NotNull PreferenceDto> grid,
                                CurrentUser user) {
        return preferences.replace(currentTeacher.require(user).getId(), semesterId, grid);
    }
}
