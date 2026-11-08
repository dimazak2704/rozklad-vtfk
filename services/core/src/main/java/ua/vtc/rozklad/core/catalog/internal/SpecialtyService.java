package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.DepartmentRepository;
import ua.vtc.rozklad.core.catalog.GroupCodes;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.SpecialtyDto;
import ua.vtc.rozklad.core.catalog.internal.StructureDtos.SpecialtyRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/**
 * Спеціальності. {@code letters} are part of every group code («4КІ1»), so they are unique and
 * cannot change while groups of the specialty exist.
 */
@Service
@Transactional(readOnly = true)
public class SpecialtyService {

    static final String LETTERS_FORMAT = "Літери спеціальності — 1–3 українські літери, як у коді групи (наприклад, КІ).";

    private static final Map<String, String> SORTABLE = Map.of("letters", "letters", "name", "name", "code", "code");

    private final SpecialtyRepository specialties;
    private final DepartmentRepository departments;
    private final StudentGroupRepository groups;

    public SpecialtyService(SpecialtyRepository specialties, DepartmentRepository departments,
                            StudentGroupRepository groups) {
        this.specialties = specialties;
        this.departments = departments;
        this.groups = groups;
    }

    public PageResponse<SpecialtyDto> list(String q, UUID departmentId, PageQuery page) {
        Page<Specialty> found = specialties.findAll(
                Specs.<Specialty>containsIgnoreCase(q, "letters", "name", "code")
                        .and(Specs.equalTo("departmentId", departmentId)),
                page.toPageable(SORTABLE, Sort.by("letters")));
        Map<UUID, Department> departmentsById = departmentsOf(found.getContent());
        return PageResponse.of(found, specialty -> StructureDtos.specialtyDto(specialty,
                departmentsById.get(specialty.getDepartmentId())));
    }

    public SpecialtyDto get(UUID id) {
        Specialty specialty = find(id);
        return StructureDtos.specialtyDto(specialty, departmentOf(specialty));
    }

    @Transactional
    public SpecialtyDto create(SpecialtyRequest request) {
        String letters = validate(request, null);
        Specialty specialty = new Specialty(letters, CatalogText.clean(request.name()));
        specialty.setCode(DepartmentService.blankToNull(request.code()));
        specialty.setDepartmentId(request.departmentId());
        specialties.save(specialty);
        return StructureDtos.specialtyDto(specialty, departmentOf(specialty));
    }

    @Transactional
    public SpecialtyDto update(UUID id, SpecialtyRequest request) {
        Specialty specialty = find(id);
        checkVersion(specialty, request.version());
        String letters = validate(request, id);
        if (!letters.equals(specialty.getLetters()) && groups.existsBySpecialtyId(id)) {
            throw new ConflictException("Літери «%s» не можна змінити: вони входять у коди груп цієї спеціальності."
                    .formatted(specialty.getLetters()));
        }
        specialty.setLetters(letters);
        specialty.setName(CatalogText.clean(request.name()));
        specialty.setCode(DepartmentService.blankToNull(request.code()));
        specialty.setDepartmentId(request.departmentId());
        specialties.flush();
        return StructureDtos.specialtyDto(specialty, departmentOf(specialty));
    }

    @Transactional
    public void delete(UUID id) {
        Specialty specialty = find(id);
        deleteOrConflict(specialties, specialty, () -> "Спеціальність «%s» не можна видалити: до неї належать групи."
                .formatted(specialty.getLetters()));
    }

    Specialty find(UUID id) {
        return specialties.findById(id).orElseThrow(() -> NotFoundException.of("Спеціальність", id));
    }

    Map<UUID, Department> departmentsOf(Collection<Specialty> list) {
        var ids = list.stream().map(Specialty::getDepartmentId).filter(Objects::nonNull).collect(Collectors.toSet());
        return departments.findAllById(ids).stream().collect(Collectors.toMap(Department::getId, Function.identity()));
    }

    private Department departmentOf(Specialty specialty) {
        return specialty.getDepartmentId() == null ? null
                : departments.findById(specialty.getDepartmentId()).orElse(null);
    }

    private String validate(SpecialtyRequest request, UUID ownId) {
        FieldErrors errors = new FieldErrors();
        String letters = GroupCodes.specialtyLetters(request.letters()).orElse(null);
        if (letters == null) {
            errors.add("letters", LETTERS_FORMAT);
        }
        if (request.departmentId() != null && !departments.existsById(request.departmentId())) {
            errors.add("departmentId", "Відділення не знайдено.");
        }
        errors.throwIfAny();
        specialties.findByLetters(letters)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Спеціальність з літерами «%s» уже існує.".formatted(letters));
                });
        return letters;
    }
}
