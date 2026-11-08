package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Employment;

/**
 * {@code POST /api/admin/import/catalog}: upsert by natural keys (department name, specialty
 * letters, teacher short name, discipline short name, room name, group code). Missing optional
 * fields keep the stored values ("merge"), so a re-import of the Excel does not wipe what the
 * admin added by hand. The whole request is one transaction: any error rejects everything.
 */
public final class CatalogImportDtos {

    private CatalogImportDtos() {
    }

    public record CatalogImportRequest(
            @Valid List<@NotNull DepartmentItem> departments,
            @Valid List<@NotNull SpecialtyItem> specialties,
            @Valid List<@NotNull NamedItem> cycleCommissions,
            @Valid List<@NotNull NamedItem> buildings,
            @Valid List<@NotNull TeacherItem> teachers,
            @Valid List<@NotNull DisciplineItem> disciplines,
            @Valid List<@NotNull RoomItem> rooms,
            @Valid List<@NotNull GroupItem> groups,
            @Valid List<@NotNull AliasItem> aliases) {

        public CatalogImportRequest {
            departments = orEmpty(departments);
            specialties = orEmpty(specialties);
            cycleCommissions = orEmpty(cycleCommissions);
            buildings = orEmpty(buildings);
            teachers = orEmpty(teachers);
            disciplines = orEmpty(disciplines);
            rooms = orEmpty(rooms);
            groups = orEmpty(groups);
            aliases = orEmpty(aliases);
        }

        private static <T> List<T> orEmpty(List<T> list) {
            return list == null ? List.of() : list;
        }
    }

    public record DepartmentItem(@NotBlank @Size(max = 200) String name, @Size(max = 50) String shortName) {
    }

    /** {@code name} empty for a new specialty → the letters are kept as the name (reported as a warning). */
    public record SpecialtyItem(
            @NotBlank @Size(max = 10) String letters,
            @Size(max = 200) String name,
            @Pattern(regexp = "\\d{3}", message = "Код спеціальності — три цифри, наприклад 123") String code,
            @Size(max = 200) String departmentName) {
    }

    public record NamedItem(@NotBlank @Size(max = 200) String name) {
    }

    /** {@code lastName} empty → taken from the short name («Кренцін М.Д.» → «Кренцін»). */
    public record TeacherItem(
            @NotBlank @Size(max = 100) String shortName,
            @Size(max = 100) String lastName,
            @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @Size(max = 100) String displayName,
            @Size(max = 200) String cycleCommissionName,
            Employment employment,
            @Size(max = 254) String email,
            Boolean needsReview) {
    }

    /** {@code name} empty → the abbreviation is kept as the name and the discipline needs review. */
    public record DisciplineItem(
            @NotBlank @Size(max = 64) String shortName,
            @Size(max = 300) String name,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Колір має вигляд #RRGGBB") String color,
            Boolean needsReview) {
    }

    public record RoomItem(
            @NotBlank @Size(max = 50) String name,
            RoomType type,
            @Min(0) @Max(RoomDtos.MAX_CAPACITY) Integer capacity,
            @Min(1) @Max(RoomDtos.MAX_PARALLEL) Integer maxParallel,
            @Size(max = 200) String buildingName) {
    }

    /** Course, letters and number come from the code; {@code subgroups} lists numbers 1..n to ensure. */
    public record GroupItem(
            @NotBlank @Size(max = 16) String code,
            @Min(1) @Max(6) Integer course,
            @Size(max = 10) String specialtyLetters,
            @Min(0) @Max(GroupDtos.MAX_GROUP_SIZE) Integer size,
            @Min(1) @Max(2) Integer shift,
            @Size(max = 50) String homeRoomName,
            @Size(max = 100) String curatorShortName,
            List<@NotNull @Min(1) @Max(GroupDtos.MAX_SUBGROUP_NUMBER) Integer> subgroups) {
    }

    /** {@code target} is the short name of the teacher or discipline the alias belongs to. */
    public record AliasItem(
            @NotNull AliasType type,
            @NotBlank @Size(max = 100) String alias,
            @NotBlank @Size(max = 100) String target) {
    }

    public enum AliasType {
        TEACHER,
        DISCIPLINE
    }

    /**
     * @param ids      per section, the natural key as sent in the request → id of the stored record
     * @param warnings things the admin should check (e.g. a specialty created without its full name)
     */
    public record CatalogImportResult(int created, int updated, int unchanged, Map<String, Map<String, UUID>> ids,
                                      List<String> warnings) {
    }
}
