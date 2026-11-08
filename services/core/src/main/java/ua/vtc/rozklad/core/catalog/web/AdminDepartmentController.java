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
import ua.vtc.rozklad.core.catalog.internal.DepartmentService;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.DepartmentDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.DepartmentRequest;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** {@code /api/admin/departments}: list ({@code q}, sort {@code name|shortName}) and CRUD. */
@RestController
@RequestMapping("/api/admin/departments")
class AdminDepartmentController {

    private final DepartmentService departments;

    AdminDepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    PageResponse<DepartmentDto> list(@RequestParam(required = false) String q, PageQuery page) {
        return departments.list(q, page);
    }

    @GetMapping("/{id}")
    DepartmentDto get(@PathVariable UUID id) {
        return departments.get(id);
    }

    @PostMapping
    ResponseEntity<DepartmentDto> create(@Valid @RequestBody DepartmentRequest request) {
        DepartmentDto created = departments.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    DepartmentDto update(@PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return departments.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        departments.delete(id);
        return ResponseEntity.noContent().build();
    }
}
