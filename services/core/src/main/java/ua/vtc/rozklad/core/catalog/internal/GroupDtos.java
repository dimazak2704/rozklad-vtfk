package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.web.Ref;

/** DTOs of groups and subgroups. */
public final class GroupDtos {

    public static final int MAX_GROUP_SIZE = 200;
    public static final int MAX_SUBGROUP_NUMBER = 9;

    private GroupDtos() {
    }

    /** {@code specialty.label} = specialty letters; subgroups are 1..n (0 = the whole group is implicit). */
    public record GroupDto(
            UUID id,
            String code,
            int course,
            UUID specialtyId,
            Ref specialty,
            Integer number,
            Integer size,
            int shift,
            TeacherRef curator,
            RoomRef homeRoom,
            boolean active,
            List<SubgroupDto> subgroups,
            long version) {
    }

    /**
     * Course, specialty and number are taken from the code («4КІ1»); when sent, they must agree with
     * it. {@code shift} defaults to 2 for the 2nd course and 1 otherwise (DOMAIN §2).
     */
    public record GroupRequest(
            @NotBlank @Size(max = 16) String code,
            @Min(1) @Max(6) Integer course,
            UUID specialtyId,
            @Min(0) @Max(9) Integer number,
            @Min(0) @Max(MAX_GROUP_SIZE) Integer size,
            @Min(1) @Max(2) Integer shift,
            UUID curatorTeacherId,
            UUID homeRoomId,
            Boolean active,
            Long version) {
    }

    public record SubgroupDto(UUID id, int number, String name, Integer size, long version) {

        static SubgroupDto of(Subgroup subgroup) {
            return new SubgroupDto(subgroup.getId(), subgroup.getNumber(), subgroup.getName(), subgroup.getSize(),
                    subgroup.getVersion());
        }
    }

    public record SubgroupRequest(
            @NotNull @Min(1) @Max(MAX_SUBGROUP_NUMBER) Integer number,
            @Size(max = 100) String name,
            @Min(0) @Max(MAX_GROUP_SIZE) Integer size,
            Long version) {
    }
}
