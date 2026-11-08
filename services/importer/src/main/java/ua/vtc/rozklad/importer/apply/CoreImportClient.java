package ua.vtc.rozklad.importer.apply;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Calls core's import endpoints with the {@code rozklad-importer} service-account token (realm role
 * SERVICE, allowed on {@code /api/admin/import/**}). Each call carries {@code Idempotency-Key}
 * {@code import-job:<id>:<step>} so core may deduplicate a retried step; the importer itself never
 * repeats a step that already succeeded.
 */
@Component
public class CoreImportClient {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(CoreImportClient.class);
    private static final int MAX_FIELD_ERRORS = 5;

    private final RestClient coreRestClient;

    public CoreImportClient(RestClient coreRestClient) {
        this.coreRestClient = coreRestClient;
    }

    public JsonNode importCatalog(CoreImportPayloads.CatalogRequest request, String idempotencyKey) {
        return post("/api/admin/import/catalog", request, idempotencyKey);
    }

    public JsonNode importTimetable(CoreImportPayloads.TimetableRequest request, String idempotencyKey) {
        return post("/api/admin/import/timetable", request, idempotencyKey);
    }

    public JsonNode importLoad(CoreImportPayloads.LoadRequest request, String idempotencyKey) {
        return post("/api/admin/import/load", request, idempotencyKey);
    }

    public JsonNode importChanges(CoreImportPayloads.ChangesRequest request, String idempotencyKey) {
        return post("/api/admin/import/changes", request, idempotencyKey);
    }

    private JsonNode post(String path, Object body, String idempotencyKey) {
        try {
            JsonNode response = coreRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? JsonNodeFactory.instance.objectNode() : response;
        } catch (RestClientResponseException e) {
            String detail = detailOf(e);
            log.warn("Core rejected {}: HTTP {} {}", path, e.getStatusCode().value(), detail);
            throw new CoreImportException(e.getStatusCode().value(), "Core відхилив імпорт (" + path + ", HTTP "
                    + e.getStatusCode().value() + ")" + (detail == null ? "." : ": " + detail), e);
        } catch (RuntimeException e) {
            log.warn("Core call {} failed: {}", path, e.toString());
            throw new CoreImportException(0, "Core недоступний: не вдалося виконати " + path + ". Спробуйте пізніше.", e);
        }
    }

    /** {@code detail} of core's ProblemDetail plus its field errors, so the admin sees what was rejected. */
    static String detailOf(RestClientResponseException e) {
        JsonNode problem;
        try {
            problem = e.getResponseBodyAs(JsonNode.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (problem == null || !problem.isObject()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        JsonNode detail = problem.get("detail");
        if (detail != null && detail.isString()) {
            text.append(detail.asString());
        }
        JsonNode errors = problem.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            List<String> fields = new ArrayList<>();
            for (JsonNode error : errors) {
                if (fields.size() == MAX_FIELD_ERRORS) {
                    fields.add("ще " + (errors.size() - MAX_FIELD_ERRORS));
                    break;
                }
                fields.add(error.path("field").asString("?") + " — " + error.path("message").asString(""));
            }
            text.append(text.isEmpty() ? "" : " ").append('(').append(String.join("; ", fields)).append(')');
        }
        return text.isEmpty() ? null : text.toString();
    }
}
