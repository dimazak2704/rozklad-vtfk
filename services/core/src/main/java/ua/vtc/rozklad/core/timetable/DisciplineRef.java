package ua.vtc.rozklad.core.timetable;

import java.util.UUID;

/** {@code DisciplineRef} of CONTRACTS.md; {@code color} is {@code #RRGGBB} or {@code null}. */
public record DisciplineRef(UUID id, String shortName, String name, String color) {
}
