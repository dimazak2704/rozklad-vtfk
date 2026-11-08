package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.ShiftWindows;

/**
 * Everything the conflict engine needs besides the lessons: catalog facts, shift windows and the
 * teacher preferences of the semester. Names are used in the Ukrainian messages.
 */
public record ConflictFacts(
        Map<UUID, GroupFact> groups,
        Map<UUID, RoomFact> rooms,
        Map<UUID, String> teacherNames,
        Map<UUID, String> disciplineNames,
        ShiftWindows shiftWindows,
        TeacherSlotPreferences preferences) {

    private static final String UNKNOWN = "?";

    public ConflictFacts {
        groups = Map.copyOf(groups);
        rooms = Map.copyOf(rooms);
        teacherNames = Map.copyOf(teacherNames);
        disciplineNames = Map.copyOf(disciplineNames);
        Objects.requireNonNull(shiftWindows, "shiftWindows");
        Objects.requireNonNull(preferences, "preferences");
    }

    public GroupFact group(UUID groupId) {
        return groupId == null ? null : groups.get(groupId);
    }

    public RoomFact room(UUID roomId) {
        return roomId == null ? null : rooms.get(roomId);
    }

    public String groupCode(UUID groupId) {
        GroupFact group = group(groupId);
        return group == null ? UNKNOWN : group.code();
    }

    public String roomName(UUID roomId) {
        RoomFact room = room(roomId);
        return room == null ? UNKNOWN : room.name();
    }

    public String teacherName(UUID teacherId) {
        return teacherId == null ? UNKNOWN : teacherNames.getOrDefault(teacherId, UNKNOWN);
    }

    public String disciplineName(UUID disciplineId) {
        return disciplineId == null ? UNKNOWN : disciplineNames.getOrDefault(disciplineId, UNKNOWN);
    }
}
