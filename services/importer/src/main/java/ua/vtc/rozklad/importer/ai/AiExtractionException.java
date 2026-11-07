package ua.vtc.rozklad.importer.ai;

/** The call happened but produced no usable result (refusal, cut-off or invalid JSON, rejected request). */
public class AiExtractionException extends RuntimeException {

    public AiExtractionException(String message) {
        super(message);
    }

    public AiExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
