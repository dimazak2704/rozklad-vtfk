package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.DepartmentRepository;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.DepartmentDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.DepartmentRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** Відділення: CRUD with a unique name (case-insensitive). */
@Service
@Transactional(readOnly = true)
public class DepartmentService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "shortName", "shortName");

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    public PageResponse<DepartmentDto> list(String q, PageQuery page) {
        return PageResponse.of(departments.findAll(Specs.containsIgnoreCase(q, "name", "shortName"),
                page.toPageable(SORTABLE, Sort.by("name"))), DepartmentDto::of);
    }

    public DepartmentDto get(UUID id) {
        return DepartmentDto.of(find(id));
    }

    @Transactional
    public DepartmentDto create(DepartmentRequest request) {
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, null);
        Department department = new Department(name, blankToNull(request.shortName()));
        return DepartmentDto.of(departments.save(department));
    }

    @Transactional
    public DepartmentDto update(UUID id, DepartmentRequest request) {
        Department department = find(id);
        checkVersion(department, request.version());
        String name = CatalogText.clean(request.name());
        ensureNameIsFree(name, id);
        department.setName(name);
        department.setShortName(blankToNull(request.shortName()));
        departments.flush();
        return DepartmentDto.of(department);
    }

    @Transactional
    public void delete(UUID id) {
        Department department = find(id);
        deleteOrConflict(departments, department, () -> "Відділення «%s» не можна видалити: до нього належать спеціальності."
                .formatted(department.getName()));
    }

    Department find(UUID id) {
        return departments.findById(id).orElseThrow(() -> NotFoundException.of("Відділення", id));
    }

    private void ensureNameIsFree(String name, UUID ownId) {
        departments.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Відділення «%s» уже існує.".formatted(existing.getName()));
                });
    }

    static String blankToNull(String value) {
        String cleaned = CatalogText.clean(value);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }
}
