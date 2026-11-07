package ua.vtc.rozklad.importer.ai;

/** Used when no API key is configured: deterministic parsers keep working, AI calls are refused. */
public class DisabledAiAdapter implements AiExtractionPort {

    public static final String MESSAGE = "Розпізнавання за допомогою ШІ вимкнено: не налаштовано ключ ANTHROPIC_API_KEY.";

    @Override
    public AiResponse complete(AiRequest request) {
        throw new AiUnavailableException(MESSAGE);
    }

    @Override
    public boolean available() {
        return false;
    }
}
