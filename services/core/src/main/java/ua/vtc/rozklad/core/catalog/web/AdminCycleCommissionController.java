package ua.vtc.rozklad.core.catalog.web;

import jakarta.validation.Valid;
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
import ua.vtc.rozklad.core.catalog.internal.CycleCommissionService;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.CycleCommissionDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.NameRequest;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** {@code /api/admin/cycle-commissions}: list ({@code q}, sort {@code name}) and CRUD. */
@RestController
@RequestMapping("/api/admin/cycle-commissions")
class AdminCycleCommissionController {

    private final CycleCommissionService commissions;

    AdminCycleCommissionController(CycleCommissionService commissions) {
        this.commissions = commissions;
    }

    @GetMapping
    PageResponse<CycleCommissionDto> list(@RequestParam(required = false) String q, PageQuery page) {
        return commissions.list(q, page);
    }

    @GetMapping("/{id}")
    CycleCommissionDto get(@PathVariable UUID id) {
        return commissions.get(id);
    }

    @PostMapping
    ResponseEntity<CycleCommissionDto> create(@Valid @RequestBody NameRequest request) {
        CycleCommissionDto created = commissions.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    CycleCommissionDto update(@PathVariable UUID id, @Valid @RequestBody NameRequest request) {
        return commissions.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        commissions.delete(id);
        return ResponseEntity.noContent().build();
    }
}
