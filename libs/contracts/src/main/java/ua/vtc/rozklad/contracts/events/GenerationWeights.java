package ua.vtc.rozklad.contracts.events;

/** Soft constraint weights of a generation request (0 disables a constraint). */
public record GenerationWeights(int windows, int brokenDays, int latePairs, int preferences, int homeRoom) {

    public static final GenerationWeights DEFAULT = new GenerationWeights(10, 10, 3, 5, 1);

    public GenerationWeights {
        if (windows < 0 || brokenDays < 0 || latePairs < 0 || preferences < 0 || homeRoom < 0) {
            throw new IllegalArgumentException("weights must not be negative");
        }
    }
}
