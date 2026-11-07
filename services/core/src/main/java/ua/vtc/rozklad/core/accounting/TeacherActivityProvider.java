package ua.vtc.rozklad.core.accounting;

import java.time.LocalDate;
import java.util.List;

/**
 * Source of teacher substitution statistics. Implemented by the substitution module, which owns
 * changes; the interface lives here so that accounting does not depend on substitution
 * (substitution already depends on accounting for the plan/fact of disciplines).
 */
public interface TeacherActivityProvider {

    /** Teachers with any activity in {@code [from, to]}; teachers without activity are omitted. */
    List<TeacherActivity> activityBetween(LocalDate from, LocalDate to);
}
