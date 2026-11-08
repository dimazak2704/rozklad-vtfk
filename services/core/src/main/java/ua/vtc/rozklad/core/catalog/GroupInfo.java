package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Read model of a group for other modules (scope checks, shift windows, capacity, home room).
 *
 * @param size      {@code null} when unknown
 * @param subgroups numbers of stored subgroups 1..n (0 = the whole group is implicit)
 */
public record GroupInfo(
        UUID id,
        String code,
        int course,
        UUID specialtyId,
        Integer number,
        Integer size,
        int shift,
        UUID curatorTeacherId,
        UUID homeRoomId,
        boolean active,
        List<Integer> subgroups) {

    public GroupInfo {
        subgroups = subgroups == null ? List.of() : List.copyOf(subgroups);
    }

    public static GroupInfo of(StudentGroup group, List<Integer> subgroups) {
        return new GroupInfo(group.getId(), group.getCode(), group.getCourse(), group.getSpecialtyId(),
                group.getNumber(), group.getSize(), group.getShift(), group.getCuratorTeacherId(),
                group.getHomeRoomId(), group.isActive(), subgroups);
    }

    public GroupRef ref() {
        return new GroupRef(id, code, course);
    }
}
