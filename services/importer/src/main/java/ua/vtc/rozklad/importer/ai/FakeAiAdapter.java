package ua.vtc.rozklad.importer.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic {@link AiExtractionPort} for tests ({@code app.ai.provider=fake}): answers are
 * scripted per purpose and returned in order; without a scripted answer the call fails loudly
 * instead of inventing data. Every request is recorded for assertions.
 */
public class FakeAiAdapter implements AiExtractionPort {

    public static final String MODEL = "fake-model";

    private final Map<AiPurpose, Deque<AiResponse>> scripted = new EnumMap<>(AiPurpose.class);
    private final List<AiRequest> requests = new ArrayList<>();

    /** Scripts a complete answer with the given JSON and token usage. */
    public synchronized FakeAiAdapter reply(AiPurpose purpose, String json, long inputTokens, long outputTokens) {
        return reply(purpose, new AiResponse(json, MODEL, inputTokens, outputTokens, AiResponse.Stop.COMPLETE));
    }

    public synchronized FakeAiAdapter reply(AiPurpose purpose, AiResponse response) {
        scripted.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(response);
        return this;
    }

    public synchronized List<AiRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized void reset() {
        scripted.clear();
        requests.clear();
    }

    @Override
    public synchronized AiResponse complete(AiRequest request) {
        requests.add(request);
        Deque<AiResponse> answers = scripted.get(request.purpose());
        if (answers == null || answers.isEmpty()) {
            throw new AiUnavailableException("Тестовий адаптер ШІ: немає підготовленої відповіді для " + request.purpose() + ".");
        }
        return answers.poll();
    }

    @Override
    public boolean available() {
        return true;
    }
}
