package ua.vtc.rozklad.importer.apply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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

    private static String detailOf(RestClientResponseException e) {
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            return problem == null ? null : problem.getDetail();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
