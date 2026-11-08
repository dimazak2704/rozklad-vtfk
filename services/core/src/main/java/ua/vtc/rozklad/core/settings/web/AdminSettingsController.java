package ua.vtc.rozklad.core.settings.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** {@code GET/PUT /api/admin/settings} (ADMIN). */
@RestController
@RequestMapping("/api/admin/settings")
class AdminSettingsController {

    private final SettingsService settings;

    AdminSettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    SettingsDto get() {
        return SettingsDto.from(settings.current(), settings.version());
    }

    @PutMapping
    SettingsDto update(@Valid @RequestBody SettingsDto body, CurrentUser user) {
        SystemSettings saved = settings.update(body.toSettings(), body.version(), user.username());
        return SettingsDto.from(saved, settings.version());
    }
}
