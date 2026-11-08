package ua.vtc.rozklad.core.calendar;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Start and end of every pair per weekday in one bell profile (Friday differs from Monday–Thursday). */
public final class BellTimes {

    private static final BellTimes NONE = new BellTimes(Map.of());

    private final Map<Integer, Slot> slots;

    private BellTimes(Map<Integer, Slot> slots) {
        this.slots = Map.copyOf(slots);
    }

    public static BellTimes none() {
        return NONE;
    }

    /** Reads the slots of a profile (load it with its slots, e.g. {@code findWithSlotsById}). */
    public static BellTimes of(BellProfile profile) {
        Map<Integer, Slot> slots = new HashMap<>();
        for (BellSlot slot : profile.getSlots()) {
            slots.put(key(slot.getWeekday(), slot.getPairNumber()), new Slot(slot.getStartsAt(), slot.getEndsAt()));
        }
        return new BellTimes(slots);
    }

    public Optional<Slot> slot(int weekday, int pair) {
        return Optional.ofNullable(slots.get(key(weekday, pair)));
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    private static int key(int weekday, int pair) {
        return weekday * 10 + pair;
    }

    public record Slot(LocalTime startsAt, LocalTime endsAt) {
    }
}
