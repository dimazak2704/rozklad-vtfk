package ua.vtc.rozklad.importer.ai;

/**
 * Port to a model that returns structured JSON for documents and text. Adapters: Anthropic (real
 * calls through the official SDK), fake (scripted answers), disabled (no API key). Prompts,
 * schemas, budget and usage accounting live in {@link AiExtractionService}, not in adapters.
 */
public interface AiExtractionPort {

    /**
     * @throws AiUnavailableException when the provider cannot be reached or is not configured
     * @throws AiExtractionException  when the provider rejects the request
     */
    AiResponse complete(AiRequest request);

    /** Whether calls can succeed at all (an API key is configured or the fake is active). */
    boolean available();
}
