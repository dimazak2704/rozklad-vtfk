package ua.vtc.rozklad.scheduler.solver.score;

/**
 * Occupied pairs of one day in the numerator and the denominator week: count of distinct pairs
 * and the first pair (0 when the week type has no lessons that day).
 */
public record WeekBalance(int numeratorPairs, int numeratorFirst, int denominatorPairs, int denominatorFirst) {

    /** «Рваний день»: the two week types differ in the number of pairs or in the first pair. */
    public boolean isBroken() {
        return numeratorPairs != denominatorPairs || numeratorFirst != denominatorFirst;
    }

    /** How broken the day is: the difference in pair counts plus 1 when the first pairs differ. */
    public int brokenness() {
        return Math.abs(numeratorPairs - denominatorPairs) + (numeratorFirst != denominatorFirst ? 1 : 0);
    }

    /** Pairs above {@code limit} in each week type, summed. */
    public int excessOver(int limit) {
        return Math.max(0, numeratorPairs - limit) + Math.max(0, denominatorPairs - limit);
    }
}
