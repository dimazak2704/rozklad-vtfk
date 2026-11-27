package ua.vtc.rozklad.scheduler.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;

/**
 * A base version that the editor's conflict check calls clean but the solver does not: group 1КІ1 has four pairs on
 * Friday with a Friday limit of three (the owner's rule), like 1КІ1, 1М, 4РТ1 and 4РТ2 in the published timetable.
 */
public final class FridayBase {

    public static final UUID GROUP = UUID.randomUUID();
    public static final UUID ROOM = UUID.randomUUID();
    public static final int FRIDAY = 5;
    public static final int FRIDAY_LIMIT = 3;

    private FridayBase() {
    }

    /** Four Friday pairs in the base; {@code newUnits} more units of new load without a base place. */
    public static ProblemSnapshot snapshot(int newUnits) {
        List<ProblemSnapshot.Unit> units = new ArrayList<>();
        for (int pair = 1; pair <= 4; pair++) {
            units.add(unit("base-" + pair, new ProblemSnapshot.PinnedSlot(FRIDAY, pair, Parity.BOTH, ROOM)));
        }
        for (int index = 1; index <= newUnits; index++) {
            units.add(unit("new-" + index, null));
        }
        return snapshot(units);
    }

    /** The same load from scratch: no unit has a base place. */
    public static ProblemSnapshot fromScratch() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            units.add(unit("new-" + index, null));
        }
        return snapshot(units);
    }

    private static ProblemSnapshot.Unit unit(String id, ProblemSnapshot.PinnedSlot initial) {
        return new ProblemSnapshot.Unit(id, UUID.randomUUID(), GROUP, 0, UUID.randomUUID(), null, UnitWeight.WHOLE,
                null, null, null, null, initial);
    }

    private static ProblemSnapshot snapshot(List<ProblemSnapshot.Unit> units) {
        List<ProblemSnapshot.Timeslot> timeslots = new ArrayList<>();
        List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        for (int weekday = 1; weekday <= FRIDAY; weekday++) {
            windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
            for (int pair = 1; pair <= 4; pair++) {
                timeslots.add(new ProblemSnapshot.Timeslot(weekday, pair));
            }
        }
        return new ProblemSnapshot(timeslots, windows,
                List.of(new ProblemSnapshot.Group(GROUP, "1КІ1", 25, 1, List.of(), FRIDAY_LIMIT)),
                List.of(new ProblemSnapshot.Room(ROOM, "304", RoomType.CLASSROOM, 30, 1)),
                List.of(), units);
    }
}
