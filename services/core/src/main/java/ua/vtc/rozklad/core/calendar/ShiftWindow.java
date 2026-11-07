package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Pairs a shift may use on a weekday: shift 1 → 1–4; shift 2 → 5–8 Mon–Thu and 4–6 Fri. */
@Entity
@Table(name = "shift_window")
public class ShiftWindow extends IdentifiableEntity {

    @Column(name = "shift", nullable = false)
    private int shift;

    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "first_pair", nullable = false)
    private int firstPair;

    @Column(name = "last_pair", nullable = false)
    private int lastPair;

    protected ShiftWindow() {
    }

    public ShiftWindow(int shift, int weekday, int firstPair, int lastPair) {
        if (shift != 1 && shift != 2) {
            throw new IllegalArgumentException("shift must be 1 or 2");
        }
        if (weekday < 1 || weekday > 5) {
            throw new IllegalArgumentException("weekday must be 1..5");
        }
        this.shift = shift;
        this.weekday = weekday;
        setPairs(firstPair, lastPair);
    }

    public int getShift() {
        return shift;
    }

    public int getWeekday() {
        return weekday;
    }

    public int getFirstPair() {
        return firstPair;
    }

    public int getLastPair() {
        return lastPair;
    }

    public void setPairs(int firstPair, int lastPair) {
        if (firstPair < 1 || lastPair > 8 || lastPair < firstPair) {
            throw new IllegalArgumentException("invalid pair range " + firstPair + ".." + lastPair);
        }
        this.firstPair = firstPair;
        this.lastPair = lastPair;
    }

    public boolean allows(int pair) {
        return pair >= firstPair && pair <= lastPair;
    }
}
