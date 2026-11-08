package ua.vtc.rozklad.core.catalog;

import java.util.Objects;
import java.util.UUID;

/** {@code TeacherRef {id, shortName, displayName}} of CONTRACTS.md (public short names only). */
public record TeacherRef(UUID id, String shortName, String displayName) {

    public TeacherRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shortName, "shortName");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static TeacherRef of(Teacher teacher) {
        return new TeacherRef(teacher.getId(), teacher.getShortName(), teacher.getDisplayName());
    }
}
