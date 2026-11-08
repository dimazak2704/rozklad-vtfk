package ua.vtc.rozklad.core.catalog;

import java.util.Objects;
import java.util.UUID;

/** {@code DisciplineRef {id, shortName, name, color}} of CONTRACTS.md; {@code color} may be null. */
public record DisciplineRef(UUID id, String shortName, String name, String color) {

    public DisciplineRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(shortName, "shortName");
        Objects.requireNonNull(name, "name");
    }

    public static DisciplineRef of(Discipline discipline) {
        return new DisciplineRef(discipline.getId(), discipline.getShortName(), discipline.getName(),
                discipline.getColor());
    }
}
