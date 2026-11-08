package ua.vtc.rozklad.core.load.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.load.internal.LoadDerivationService;
import ua.vtc.rozklad.core.load.internal.LoadDtos.DerivedLoadResult;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadItemDto;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadItemRequest;
import ua.vtc.rozklad.core.load.internal.LoadService;
import ua.vtc.rozklad.core.shared.web.Created;

/**
 * {@code /api/admin/load?semesterId&groupId}: load items of a semester (optionally one group), CRUD
 * and {@code POST /derive-from-version/{versionId}} (TIMETABLE_DERIVED items from a weekly template).
 */
@RestController
@RequestMapping("/api/admin/load")
class AdminLoadController {

    private final LoadService load;
    private final LoadDerivationService derivation;

    AdminLoadController(LoadService load, LoadDerivationService derivation) {
        this.load = load;
        this.derivation = derivation;
    }

    @GetMapping
    List<LoadItemDto> list(@RequestParam UUID semesterId, @RequestParam(required = false) UUID groupId) {
        return load.list(semesterId, groupId);
    }

    @GetMapping("/{id}")
    LoadItemDto get(@PathVariable UUID id) {
        return load.get(id);
    }

    @PostMapping
    ResponseEntity<LoadItemDto> create(@Valid @RequestBody LoadItemRequest request) {
        LoadItemDto created = load.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    LoadItemDto update(@PathVariable UUID id, @Valid @RequestBody LoadItemRequest request) {
        return load.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        load.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/derive-from-version/{versionId}")
    DerivedLoadResult derive(@PathVariable UUID versionId) {
        return derivation.derive(versionId);
    }
}
