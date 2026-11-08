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
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.internal.AliasService;
import ua.vtc.rozklad.core.catalog.internal.CompetenceService;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.AliasDto;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.AliasRequest;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.CompetenceRequest;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.CompetencesRequest;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherDto;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherRequest;
import ua.vtc.rozklad.core.catalog.internal.TeacherService;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/**
 * {@code /api/admin/teachers}: list (filters {@code q, cycleCommissionId, active, needsReview,
 * linked}; sort {@code lastName|shortName|displayName|employment|active}), CRUD, aliases
 * ({@code /{id}/aliases}) and competences ({@code /{id}/disciplines}).
 */
@RestController
@RequestMapping("/api/admin/teachers")
class AdminTeacherController {

    private final TeacherService teachers;
    private final AliasService aliases;
    private final CompetenceService competences;

    AdminTeacherController(TeacherService teachers, AliasService aliases, CompetenceService competences) {
        this.teachers = teachers;
        this.aliases = aliases;
        this.competences = competences;
    }

    @GetMapping
    PageResponse<TeacherDto> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) UUID cycleCommissionId,
                                  @RequestParam(required = false) Boolean active,
                                  @RequestParam(required = false) Boolean needsReview,
                                  @RequestParam(required = false) Boolean linked, PageQuery page) {
        return teachers.list(q, cycleCommissionId, active, needsReview, linked, page);
    }

    @GetMapping("/{id}")
    TeacherDto get(@PathVariable UUID id) {
        return teachers.get(id);
    }

    @PostMapping
    ResponseEntity<TeacherDto> create(@Valid @RequestBody TeacherRequest request) {
        TeacherDto created = teachers.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    TeacherDto update(@PathVariable UUID id, @Valid @RequestBody TeacherRequest request) {
        return teachers.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        teachers.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/aliases")
    List<AliasDto> aliases(@PathVariable UUID id) {
        return aliases.teacherAliases(id);
    }

    @PostMapping("/{id}/aliases")
    ResponseEntity<AliasDto> addAlias(@PathVariable UUID id, @Valid @RequestBody AliasRequest request) {
        AliasDto created = aliases.addTeacherAlias(id, request.alias());
        return Created.at(created.id(), created);
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    ResponseEntity<Void> removeAlias(@PathVariable UUID id, @PathVariable UUID aliasId) {
        aliases.removeTeacherAlias(id, aliasId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/disciplines")
    List<DisciplineRef> disciplines(@PathVariable UUID id) {
        return competences.list(id);
    }

    @PutMapping("/{id}/disciplines")
    List<DisciplineRef> replaceDisciplines(@PathVariable UUID id, @Valid @RequestBody CompetencesRequest request) {
        return competences.replace(id, request.disciplineIds());
    }

    @PostMapping("/{id}/disciplines")
    List<DisciplineRef> addDiscipline(@PathVariable UUID id, @Valid @RequestBody CompetenceRequest request) {
        return competences.add(id, request.disciplineId());
    }

    @DeleteMapping("/{id}/disciplines/{disciplineId}")
    ResponseEntity<Void> removeDiscipline(@PathVariable UUID id, @PathVariable UUID disciplineId) {
        competences.remove(id, disciplineId);
        return ResponseEntity.noContent().build();
    }
}
