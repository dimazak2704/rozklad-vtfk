package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A scheduled dated lesson that a calendar refresh keeps as it is (see
 * {@link LessonMaterializationRepository#keptScheduled}).
 *
 * @param fromOrder created by an applied change (otherwise kept for an open decision)
 */
record StrandedLesson(UUID id, LocalDate date, int pair, int subgroup, UUID groupId, String groupCode, int course,
                      String disciplineName, String teacherName, boolean fromOrder) {
}
