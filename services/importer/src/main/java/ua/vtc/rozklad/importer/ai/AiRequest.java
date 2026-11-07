package ua.vtc.rozklad.importer.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One structured-output call: system prompt, optional documents, the user instruction and the JSON
 * schema the answer must follow (property order is kept).
 *
 * @param effort output effort ({@code low|medium|high|xhigh|max})
 */
public record AiRequest(AiPurpose purpose, String model, String system, List<AiDocument> documents, String instruction,
                        Map<String, Object> schema, long maxTokens, String effort) {

    public AiRequest {
        documents = List.copyOf(documents);
        schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }
}
