package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.Building;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.shared.web.Ref;

/**
 * DTOs of the college structure: departments, specialties, cycle commissions, buildings.
 * {@code version} in requests is optional; when sent it must equal the stored one (409 otherwise).
 */
public final class StructureDtos {

    private StructureDtos() {
    }

    public record DepartmentDto(UUID id, String name, String shortName, long version) {

        public static DepartmentDto of(Department department) {
            return new DepartmentDto(department.getId(), department.getName(), department.getShortName(),
                    department.getVersion());
        }
    }

    public record DepartmentRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 50) String shortName,
            Long version) {
    }

    public record SpecialtyDto(UUID id, String code, String letters, String name, UUID departmentId, Ref department,
                               long version) {
    }

    /**
     * @param letters specialty letters of group codes («КІ»); Latin look-alikes are converted
     * @param code    three-digit code of the national classifier («123»), optional
     */
    public record SpecialtyRequest(
            @Pattern(regexp = "\\d{3}", message = "Код спеціальності — три цифри, наприклад 123") String code,
            @NotBlank @Size(max = 10) String letters,
            @NotBlank @Size(max = 200) String name,
            UUID departmentId,
            Long version) {
    }

    public record CycleCommissionDto(UUID id, String name, long version) {

        public static CycleCommissionDto of(CycleCommission commission) {
            return new CycleCommissionDto(commission.getId(), commission.getName(), commission.getVersion());
        }
    }

    public record BuildingDto(UUID id, String name, long version) {

        public static BuildingDto of(Building building) {
            return new BuildingDto(building.getId(), building.getName(), building.getVersion());
        }
    }

    /** Body of cycle commissions and buildings: they have only a unique name. */
    public record NameRequest(@NotBlank @Size(max = 200) String name, Long version) {
    }

    static SpecialtyDto specialtyDto(Specialty specialty, Department department) {
        Ref departmentRef = department == null ? null : new Ref(department.getId(), department.getName());
        return new SpecialtyDto(specialty.getId(), specialty.getCode(), specialty.getLetters(), specialty.getName(),
                specialty.getDepartmentId(), departmentRef, specialty.getVersion());
    }
}
