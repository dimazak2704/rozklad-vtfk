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
import ua.vtc.rozklad.core.catalog.internal.BuildingService;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.BuildingDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.NameRequest;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** {@code /api/admin/buildings}: list ({@code q}, sort {@code name}) and CRUD. */
@RestController
@RequestMapping("/api/admin/buildings")
class AdminBuildingController {

    private final BuildingService buildings;

    AdminBuildingController(BuildingService buildings) {
        this.buildings = buildings;
    }

    @GetMapping
    PageResponse<BuildingDto> list(@RequestParam(required = false) String q, PageQuery page) {
        return buildings.list(q, page);
    }

    @GetMapping("/{id}")
    BuildingDto get(@PathVariable UUID id) {
        return buildings.get(id);
    }

    @PostMapping
    ResponseEntity<BuildingDto> create(@Valid @RequestBody NameRequest request) {
        BuildingDto created = buildings.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    BuildingDto update(@PathVariable UUID id, @Valid @RequestBody NameRequest request) {
        return buildings.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        buildings.delete(id);
        return ResponseEntity.noContent().build();
    }
}
