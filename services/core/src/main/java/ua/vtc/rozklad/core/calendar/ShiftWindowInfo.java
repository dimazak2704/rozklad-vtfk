package ua.vtc.rozklad.core.calendar;

/** Pairs a shift may use on a weekday: shift 1 → 1–4; shift 2 → 5–8 Mon–Thu and 4–6 Fri. */
public record ShiftWindowInfo(int shift, int weekday, int firstPair, int lastPair) {

    public static ShiftWindowInfo of(ShiftWindow window) {
        return new ShiftWindowInfo(window.getShift(), window.getWeekday(), window.getFirstPair(), window.getLastPair());
    }

    public boolean allows(int pair) {
        return pair >= firstPair && pair <= lastPair;
    }
}
