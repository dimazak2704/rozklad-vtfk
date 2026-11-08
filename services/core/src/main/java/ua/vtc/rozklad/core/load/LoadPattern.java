package ua.vtc.rozklad.core.load;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Weekly units of a load item (DOMAIN §4): {@code pairsPerWeek = hoursPerWeek / hoursPerPair};
 * {@code whole = floor(p)}; a remainder in [0.25, 0.75) adds one HALF unit (numerator or
 * denominator only, chosen by the solver), a remainder ≥ 0.75 adds one more whole pair.
 * With 2 hours per pair: 3 h → 1 + ½, 4 h → 2, 2 h → 1, 1 h → ½.
 *
 * @param whole pairs every week
 * @param half  0 or 1 pair every other week
 */
public record LoadPattern(int whole, int half) {

    private static final BigDecimal QUARTER = new BigDecimal("0.25");
    private static final BigDecimal THREE_QUARTERS = new BigDecimal("0.75");

    public LoadPattern {
        if (whole < 0 || half < 0 || half > 1) {
            throw new IllegalArgumentException("invalid pattern " + whole + " + " + half + "/2");
        }
    }

    public static LoadPattern of(BigDecimal hoursPerWeek, int hoursPerPair) {
        Objects.requireNonNull(hoursPerWeek, "hoursPerWeek");
        if (hoursPerPair < 1) {
            throw new IllegalArgumentException("hoursPerPair must be >= 1");
        }
        BigDecimal pairs = hoursPerWeek.divide(BigDecimal.valueOf(hoursPerPair), 4, RoundingMode.HALF_UP);
        int whole = pairs.setScale(0, RoundingMode.FLOOR).intValueExact();
        BigDecimal remainder = pairs.subtract(BigDecimal.valueOf(whole));
        if (remainder.compareTo(THREE_QUARTERS) >= 0) {
            return new LoadPattern(whole + 1, 0);
        }
        if (remainder.compareTo(QUARTER) >= 0) {
            return new LoadPattern(whole, 1);
        }
        return new LoadPattern(whole, 0);
    }

    /** Pairs per week as scheduled: whole + ½ per half unit. */
    public BigDecimal pairsPerWeek() {
        return BigDecimal.valueOf(whole).add(new BigDecimal("0.5").multiply(BigDecimal.valueOf(half)));
    }
}
