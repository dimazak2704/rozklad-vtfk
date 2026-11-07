package ua.vtc.rozklad.importer.ai;

/**
 * The model's answer: the JSON text (structured output), the model that produced it and the token
 * usage; {@code stop} tells whether the answer is complete.
 */
public record AiResponse(String text, String model, long inputTokens, long outputTokens, Stop stop) {

    public enum Stop {
        /** Finished normally. */
        COMPLETE,
        /** The model or its safety classifier declined; the text is empty or partial. */
        REFUSED,
        /** The output token cap was reached; the JSON is cut off. */
        TRUNCATED
    }
}
