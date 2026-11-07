package ua.vtc.rozklad.core.timetable.internal.importing;

import java.util.UUID;

/** Draft version created from an imported timetable, with its quality at a glance. */
public record ImportTimetableResult(UUID versionId, String name, int lessonCount, int hardConflicts,
                                    int softWarnings) {
}
