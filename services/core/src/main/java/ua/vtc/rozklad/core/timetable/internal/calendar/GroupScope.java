package ua.vtc.rozklad.core.timetable.internal.calendar;

import java.util.Objects;
import java.util.UUID;

/** What a calendar period needs to know about a group to decide whether it applies. */
public record GroupScope(UUID groupId, int course) {

    public GroupScope {
        Objects.requireNonNull(groupId, "groupId");
    }
}
