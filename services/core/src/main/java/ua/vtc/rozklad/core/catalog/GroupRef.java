package ua.vtc.rozklad.core.catalog;

import java.util.Objects;
import java.util.UUID;

/** {@code GroupRef {id, code, course}} of CONTRACTS.md. */
public record GroupRef(UUID id, String code, int course) {

    public GroupRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
    }

    public static GroupRef of(StudentGroup group) {
        return new GroupRef(group.getId(), group.getCode(), group.getCourse());
    }
}
