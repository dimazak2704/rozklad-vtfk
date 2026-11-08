package ua.vtc.rozklad.core.calendar.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.BellProfileService;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellProfileDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellProfileRequest;
import ua.vtc.rozklad.core.shared.web.Created;

/** {@code /api/admin/bell-profiles}: CRUD of bell profiles with their slots (PUT replaces all slots). */
@RestController
@RequestMapping("/api/admin/bell-profiles")
class AdminBellProfileController {

    private final BellProfileService profiles;

    AdminBellProfileController(BellProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    List<BellProfileDto> list() {
        return profiles.list();
    }

    @GetMapping("/{id}")
    BellProfileDto get(@PathVariable UUID id) {
        return profiles.get(id);
    }

    @PostMapping
    ResponseEntity<BellProfileDto> create(@Valid @RequestBody BellProfileRequest request) {
        BellProfileDto created = profiles.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    BellProfileDto update(@PathVariable UUID id, @Valid @RequestBody BellProfileRequest request) {
        return profiles.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.delete(id);
        return ResponseEntity.noContent().build();
    }
}
