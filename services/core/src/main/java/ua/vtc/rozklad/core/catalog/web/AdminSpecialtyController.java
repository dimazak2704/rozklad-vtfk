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
import ua.vtc.rozklad.core.catalog.internal.SpecialtyService;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.SpecialtyDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.SpecialtyRequest;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** {@code /api/admin/specialties}: list ({@code q}, {@code departmentId}; sort {@code letters|name|code}) and CRUD. */
@RestController
@RequestMapping("/api/admin/specialties")
class AdminSpecialtyController {

    private final SpecialtyService specialties;

    AdminSpecialtyController(SpecialtyService specialties) {
        this.specialties = specialties;
    }

    @GetMapping
    PageResponse<SpecialtyDto> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) UUID departmentId, PageQuery page) {
        return specialties.list(q, departmentId, page);
    }

    @GetMapping("/{id}")
    SpecialtyDto get(@PathVariable UUID id) {
        return specialties.get(id);
    }

    @PostMapping
    ResponseEntity<SpecialtyDto> create(@Valid @RequestBody SpecialtyRequest request) {
        SpecialtyDto created = specialties.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    SpecialtyDto update(@PathVariable UUID id, @Valid @RequestBody SpecialtyRequest request) {
        return specialties.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        specialties.delete(id);
        return ResponseEntity.noContent().build();
    }
}
