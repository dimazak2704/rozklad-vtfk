package ua.vtc.rozklad.core.catalog.web;

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
import ua.vtc.rozklad.core.catalog.internal.AliasService;
import ua.vtc.rozklad.core.catalog.internal.DisciplineDtos.DisciplineDto;
import ua.vtc.rozklad.core.catalog.internal.DisciplineDtos.DisciplineRequest;
import ua.vtc.rozklad.core.catalog.internal.DisciplineService;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.AliasDto;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.AliasRequest;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/**
 * {@code /api/admin/disciplines}: list (filters {@code q, active, needsReview}; sort
 * {@code name|shortName|active|needsReview}), CRUD and aliases ({@code /{id}/aliases}).
 */
@RestController
@RequestMapping("/api/admin/disciplines")
class AdminDisciplineController {

    private final DisciplineService disciplines;
    private final AliasService aliases;

    AdminDisciplineController(DisciplineService disciplines, AliasService aliases) {
        this.disciplines = disciplines;
        this.aliases = aliases;
    }

    @GetMapping
    PageResponse<DisciplineDto> list(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) Boolean active,
                                     @RequestParam(required = false) Boolean needsReview, PageQuery page) {
        return disciplines.list(q, active, needsReview, page);
    }

    @GetMapping("/{id}")
    DisciplineDto get(@PathVariable UUID id) {
        return disciplines.get(id);
    }

    @PostMapping
    ResponseEntity<DisciplineDto> create(@Valid @RequestBody DisciplineRequest request) {
        DisciplineDto created = disciplines.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    DisciplineDto update(@PathVariable UUID id, @Valid @RequestBody DisciplineRequest request) {
        return disciplines.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        disciplines.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/aliases")
    List<AliasDto> aliases(@PathVariable UUID id) {
        return aliases.disciplineAliases(id);
    }

    @PostMapping("/{id}/aliases")
    ResponseEntity<AliasDto> addAlias(@PathVariable UUID id, @Valid @RequestBody AliasRequest request) {
        AliasDto created = aliases.addDisciplineAlias(id, request.alias());
        return Created.at(created.id(), created);
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    ResponseEntity<Void> removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId) {
        aliases.removeDisciplineAlias(id, aliasId);
        return ResponseEntity.noContent().build();
    }
}
