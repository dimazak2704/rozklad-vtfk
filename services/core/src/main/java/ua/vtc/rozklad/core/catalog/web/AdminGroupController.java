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
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.GroupDto;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.GroupRequest;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.SubgroupDto;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.SubgroupRequest;
import ua.vtc.rozklad.core.catalog.internal.GroupService;
import ua.vtc.rozklad.core.shared.web.Created;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/**
 * {@code /api/admin/groups}: list (filters {@code q, course, specialtyId, shift, active}; sort
 * {@code code|course|shift|size|active}), CRUD and subgroups ({@code /{id}/subgroups}).
 */
@RestController
@RequestMapping("/api/admin/groups")
class AdminGroupController {

    private final GroupService groups;

    AdminGroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    PageResponse<GroupDto> list(@RequestParam(required = false) String q,
                                @RequestParam(required = false) Integer course,
                                @RequestParam(required = false) UUID specialtyId,
                                @RequestParam(required = false) Integer shift,
                                @RequestParam(required = false) Boolean active, PageQuery page) {
        return groups.list(q, course, specialtyId, shift, active, page);
    }

    @GetMapping("/{id}")
    GroupDto get(@PathVariable UUID id) {
        return groups.get(id);
    }

    @PostMapping
    ResponseEntity<GroupDto> create(@Valid @RequestBody GroupRequest request) {
        GroupDto created = groups.create(request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}")
    GroupDto update(@PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        return groups.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        groups.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/subgroups")
    List<SubgroupDto> subgroups(@PathVariable UUID id) {
        return groups.subgroups(id);
    }

    @PostMapping("/{id}/subgroups")
    ResponseEntity<SubgroupDto> addSubgroup(@PathVariable UUID id, @Valid @RequestBody SubgroupRequest request) {
        SubgroupDto created = groups.addSubgroup(id, request);
        return Created.at(created.id(), created);
    }

    @PutMapping("/{id}/subgroups/{subgroupId}")
    SubgroupDto updateSubgroup(@PathVariable UUID id, @PathVariable UUID subgroupId,
                               @Valid @RequestBody SubgroupRequest request) {
        return groups.updateSubgroup(id, subgroupId, request);
    }

    @DeleteMapping("/{id}/subgroups/{subgroupId}")
    ResponseEntity<Void> deleteSubgroup(@PathVariable UUID id, @PathVariable UUID subgroupId) {
        groups.deleteSubgroup(id, subgroupId);
        return ResponseEntity.noContent().build();
    }
}
