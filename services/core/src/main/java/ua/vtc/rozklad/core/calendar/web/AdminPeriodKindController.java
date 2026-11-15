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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.PeriodKindDto;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.PeriodKindRequest;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindService;
import ua.vtc.rozklad.core.shared.web.Created;

/**
 * {@code /api/admin/period-kinds}: «Види періодів». {@code PUT …?dryRun=true} answers what the edit would
 * do ({@code PeriodKindImpactDto}) and saves nothing.
 */
@RestController
@RequestMapping("/api/admin/period-kinds")
class AdminPeriodKindController {

    private final PeriodKindService kinds;

    AdminPeriodKindController(PeriodKindService kinds) {
        this.kinds = kinds;
    }

    @GetMapping
    List<PeriodKindDto> list(@RequestParam(required = false) Boolean active) {
        return kinds.list(active);
    }

    @GetMapping("/{id}")
    PeriodKindDto get(@PathVariable UUID id) {
        return kinds.get(id);
    }

    @PostMapping
    ResponseEntity<PeriodKindDto> create(@Valid @RequestBody PeriodKindRequest request) {
        PeriodKindDto created = kinds.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean dryRun,
                             @Valid @RequestBody PeriodKindRequest request) {
        return ResponseEntity.ok(dryRun ? kinds.preview(id, request) : kinds.update(id, request));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        kinds.delete(id);
        return ResponseEntity.noContent().build();
    }
}
