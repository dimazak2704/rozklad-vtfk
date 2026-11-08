package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;

/** A template lesson that an open decision keeps across a republication, with its printed names. */
record PinnedLesson(UUID id, LocalDate date, int pair, int subgroup, UUID versionId, String groupCode,
                    String disciplineName, String teacherName) {
}
