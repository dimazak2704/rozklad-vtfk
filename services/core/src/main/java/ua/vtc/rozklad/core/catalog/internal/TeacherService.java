package ua.vtc.rozklad.core.catalog.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.Employment;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherDto;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.persistence.Specs;
import ua.vtc.rozklad.core.shared.web.PageQuery;
import ua.vtc.rozklad.core.shared.web.PageResponse;
import ua.vtc.rozklad.core.shared.web.Ref;

/**
 * Викладачі. The short name («Кренцін М.Д.») is the natural key of imports and orders: it is
 * unique by its normalized key (DOMAIN §10) and must not collide with another teacher's alias.
 * The Keycloak link is set only by the invite flow (identity module).
 */
@Service
@Transactional(readOnly = true)
public class TeacherService {

    private static final Map<String, String> SORTABLE = Map.of("lastName", "lastName", "shortName", "shortName",
            "displayName", "displayName", "employment", "employment", "active", "active");

    private final TeacherRepository teachers;
    private final TeacherAliasRepository aliases;
    private final CycleCommissionRepository commissions;

    public TeacherService(TeacherRepository teachers, TeacherAliasRepository aliases,
                          CycleCommissionRepository commissions) {
        this.teachers = teachers;
        this.aliases = aliases;
        this.commissions = commissions;
    }

    /**
     * @param linked {@code true} = has an account, {@code false} = has none, {@code null} = all
     */
    public PageResponse<TeacherDto> list(String q, UUID cycleCommissionId, Boolean active, Boolean needsReview,
                                         Boolean linked, PageQuery page) {
        Specification<Teacher> filter = Specs.<Teacher>containsIgnoreCase(q, "lastName", "firstName", "shortName",
                        "displayName")
                .and(Specs.equalTo("cycleCommissionId", cycleCommissionId))
                .and(Specs.equalTo("active", active))
                .and(Specs.equalTo("needsReview", needsReview))
                .and(linkedFilter(linked));
        Page<Teacher> found = teachers.findAll(filter, page.toPageable(SORTABLE, Sort.by("shortName")));
        Map<UUID, Ref> commissionRefs = commissionRefs(found.getContent().stream()
                .map(Teacher::getCycleCommissionId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.of(found, teacher -> TeacherDto.of(teacher, commissionRefs.get(teacher.getCycleCommissionId())));
    }

    public TeacherDto get(UUID id) {
        return toDto(find(id));
    }

    @Transactional
    public TeacherDto create(TeacherRequest request) {
        Values values = resolve(request, null);
        Teacher teacher = new Teacher(values.lastName(), values.shortName(), values.displayName());
        values.copyTo(teacher, request);
        teachers.save(teacher);
        return toDto(teacher);
    }

    @Transactional
    public TeacherDto update(UUID id, TeacherRequest request) {
        Teacher teacher = find(id);
        checkVersion(teacher, request.version());
        resolve(request, id).copyTo(teacher, request);
        teachers.flush();
        return toDto(teacher);
    }

    @Transactional
    public void delete(UUID id) {
        Teacher teacher = find(id);
        deleteOrConflict(teachers, teacher, () -> ("Викладача «%s» не можна видалити: у нього є заняття, навантаження, "
                + "кураторство або заміни. Позначте його неактивним.").formatted(teacher.getShortName()));
    }

    public Teacher find(UUID id) {
        return teachers.findById(id).orElseThrow(() -> NotFoundException.of("Викладача", id));
    }

    TeacherDto toDto(Teacher teacher) {
        Ref commission = teacher.getCycleCommissionId() == null ? null
                : commissions.findById(teacher.getCycleCommissionId())
                .map(found -> new Ref(found.getId(), found.getName())).orElse(null);
        return TeacherDto.of(teacher, commission);
    }

    /**
     * Teacher whose short name has the same normalized key, if any (another spelling of the same
     * person must become an alias, not a second teacher).
     */
    Optional<Teacher> findByShortNameKey(String key, UUID exceptId) {
        return teachers.findAll().stream()
                .filter(teacher -> !teacher.getId().equals(exceptId))
                .filter(teacher -> CatalogText.key(teacher.getShortName()).equals(key))
                .findFirst();
    }

    /** Validates the request and derives the short and display names when they are empty. */
    private Values resolve(TeacherRequest request, UUID ownId) {
        String lastName = CatalogText.clean(request.lastName());
        String firstName = DepartmentService.blankToNull(request.firstName());
        String middleName = DepartmentService.blankToNull(request.middleName());
        String shortName = Optional.ofNullable(DepartmentService.blankToNull(request.shortName()))
                .orElseGet(() -> TeacherNames.shortName(lastName, firstName, middleName));
        String displayName = Optional.ofNullable(DepartmentService.blankToNull(request.displayName())).orElse(lastName);

        FieldErrors errors = new FieldErrors();
        if (request.cycleCommissionId() != null && !commissions.existsById(request.cycleCommissionId())) {
            errors.add("cycleCommissionId", "Циклову комісію не знайдено.");
        }
        String key = CatalogText.key(shortName);
        if (key.isEmpty()) {
            errors.add("shortName", "Коротке ім'я має містити літери.");
        }
        errors.throwIfAny();
        findByShortNameKey(key, ownId).ifPresent(existing -> {
            throw new ConflictException("Викладач «%s» уже існує.".formatted(existing.getShortName()));
        });
        aliases.findByNormalized(key)
                .filter(alias -> !alias.getTeacherId().equals(ownId))
                .ifPresent(alias -> {
                    throw new ConflictException("Ім'я «%s» уже записане як псевдонім іншого викладача.".formatted(shortName));
                });
        return new Values(lastName, firstName, middleName, shortName, displayName);
    }

    private Map<UUID, Ref> commissionRefs(Set<UUID> ids) {
        return commissions.findAllById(ids).stream()
                .collect(Collectors.toMap(CycleCommission::getId, c -> new Ref(c.getId(), c.getName())));
    }

    private static Specification<Teacher> linkedFilter(Boolean linked) {
        if (linked == null) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> linked ? cb.isNotNull(root.get("keycloakUserId")) : cb.isNull(root.get("keycloakUserId"));
    }

    private record Values(String lastName, String firstName, String middleName, String shortName,
                          String displayName) {

        void copyTo(Teacher teacher, TeacherRequest request) {
            teacher.setLastName(lastName);
            teacher.setFirstName(firstName);
            teacher.setMiddleName(middleName);
            teacher.setShortName(shortName);
            teacher.setDisplayName(displayName);
            teacher.setCycleCommissionId(request.cycleCommissionId());
            teacher.setEmployment(request.employment() == null ? Employment.STAFF : request.employment());
            teacher.setEmail(DepartmentService.blankToNull(request.email()));
            teacher.setActive(request.active() == null || request.active());
            teacher.setNeedsReview(request.needsReview() != null && request.needsReview());
        }
    }
}
