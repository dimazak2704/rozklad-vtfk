package ua.vtc.rozklad.core.timetable;

import java.util.UUID;

/** {@code TeacherRef} of CONTRACTS.md: public short names only («Кренцін М.Д.», «Кренцін»). */
public record TeacherRef(UUID id, String shortName, String displayName) {
}
