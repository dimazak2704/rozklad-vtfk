package ua.vtc.rozklad.scheduler.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/** Puts every unit of a problem where a given assignment says (e.g. the planted timetable). */
public final class Placements {

    private Placements() {
    }

    public static Timetable apply(Timetable timetable, List<Assignment> assignments) {
        Map<String, Assignment> byUnit = assignments.stream()
                .collect(Collectors.toMap(Assignment::unitId, Function.identity()));
        Map<UUID, Room> rooms = timetable.getRooms().stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));
        for (LessonUnit unit : timetable.getUnits()) {
            Assignment assignment = byUnit.get(unit.getId());
            unit.setTimeslot(new Timeslot(assignment.weekday(), assignment.pair()));
            unit.setParity(assignment.parity());
            unit.setRoom(assignment.roomId() == null ? Room.NONE : rooms.get(assignment.roomId()));
        }
        return timetable;
    }
}
