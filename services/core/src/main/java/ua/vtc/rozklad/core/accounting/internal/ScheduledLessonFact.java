package ua.vtc.rozklad.core.accounting.internal;

import java.time.LocalDate;
import java.util.UUID;

/** Projection of a SCHEDULED lesson for accounting (JPQL constructor expression). */
public record ScheduledLessonFact(UUID lessonId, UUID groupId, UUID disciplineId, int subgroup, LocalDate date,
                                  int pair) {
}
