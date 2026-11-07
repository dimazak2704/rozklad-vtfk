package ua.vtc.rozklad.contracts;

/** Week parity of a lesson: every week, numerator weeks only or denominator weeks only. */
public enum Parity {
    BOTH,
    NUMERATOR,
    DENOMINATOR;

    /** {@code true} when lessons with these parities can meet in the same week. */
    public boolean overlaps(Parity other) {
        return this == BOTH || other == BOTH || this == other;
    }
}
