package ua.vtc.rozklad.core.timetable;

import java.util.UUID;

/** {@code GroupRef} of CONTRACTS.md: short reference to a group in schedule views. */
public record GroupRef(UUID id, String code, int course) {
}
