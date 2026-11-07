package ua.vtc.rozklad.core.accounting;

import java.util.UUID;

/**
 * Substitution activity of a teacher in a period: changes where the teacher replaced a colleague
 * (count and pairs) and own pairs that were taken over or cancelled by changes.
 */
public record TeacherActivity(UUID teacherId, int substitutions, int substitutedPairs, int missedPairs) {
}
