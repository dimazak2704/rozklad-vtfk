package ua.vtc.rozklad.core.substitution;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a publication changed: applied change ids, groups and teachers whose lessons changed
 * (original and new teachers) and the affected dates — enough for {@code schedule.changed}.
 */
public record AppliedChanges(List<UUID> changeIds, Set<UUID> groupIds, Set<UUID> teacherIds, Set<LocalDate> dates) {

    public AppliedChanges {
        changeIds = List.copyOf(changeIds);
        groupIds = Set.copyOf(groupIds);
        teacherIds = Set.copyOf(teacherIds);
        dates = Set.copyOf(dates);
    }
}
