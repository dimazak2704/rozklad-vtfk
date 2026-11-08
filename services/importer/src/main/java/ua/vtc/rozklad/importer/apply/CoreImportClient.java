package ua.vtc.rozklad.importer.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern INDEXED = Pattern.compile("^([A-Za-z]+)\\[(\\d+)]");
    private static final Map<String, String> POSITIONS = Map.of(
            "items", "рядок", "teachers", "викладач", "groups", "група", "disciplines", "дисципліна",
            "rooms", "аудиторія", "aliases", "синонім", "lessons", "заняття");

    private final RestClient coreRestClient;

    public CoreImportClient(RestClient coreRestClient) {
        this.coreRestClient = coreRestClient;
    }

    public JsonNode importCatalog(CoreImportPayloads.CatalogRequest request, String idempotencyKey) {
        return post("/api/admin/import/catalog", "довідники (групи, викладачів, дисципліни, аудиторії)", request,
                idempotencyKey);
    }

    public JsonNode importTimetable(CoreImportPayloads.TimetableRequest request, String idempotencyKey) {
        return post("/api/admin/import/timetable", "розклад", request, idempotencyKey);
    }

    public JsonNode importLoad(CoreImportPayloads.LoadRequest request, String idempotencyKey) {
        return post("/api/admin/import/load", "навантаження", request, idempotencyKey);
    }

    public JsonNode importChanges(CoreImportPayloads.ChangesRequest request, String idempotencyKey) {
        return post("/api/admin/import/changes", "розпорядження", request, idempotencyKey);
    }

    /**
     * The exception message is shown to the dispatcher: plain Ukrainian, without paths, HTTP codes
     * or JSON field names; those go to the log.
     */
    private JsonNode post(String path, String what, Object body, String idempotencyKey) {
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
            int status = e.getStatusCode().value();
            String detail = detailOf(e);
            log.warn("Core rejected {}: HTTP {} {}", path, status, detail);
            throw new CoreImportException(status, rejection(status, what, detail), e);
        } catch (RuntimeException e) {
            log.warn("Core call {} failed: {}", path, e.toString());
            throw new CoreImportException(0, "Сервер розкладу недоступний — " + what + " не передано. Спробуйте пізніше.",
                    e);
        }
    }

    static String rejection(int status, String what, String detail) {
        if (status == 401 || status == 403) {
            return "Сервер розкладу відмовив імпорту в доступі — " + what + " не передано. Повідомте адміністратора "
                    + "системи.";
        }
        if (status >= 500) {
            return "Сервер розкладу не зміг зберегти " + what + " через внутрішню помилку. Спробуйте пізніше.";
        }
        return "Сервер розкладу не прийняв " + what + (detail == null ? "." : ": " + detail);
    }

    /**
     * {@code detail} of core's ProblemDetail plus its field errors, so the admin sees what was
     * rejected; a list index becomes a human position («рядок 3»), the rest of the field path is dropped.
     */
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
                String position = position(error.path("field").asString(""));
                String message = error.path("message").asString("");
                fields.add(position == null ? message : position + ": " + message);
            }
            text.append(text.isEmpty() ? "" : " ").append('(').append(String.join("; ", fields)).append(')');
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** «items[2].groupCode» → «рядок 3»; a field without a list index → {@code null}. */
    static String position(String field) {
        Matcher matcher = INDEXED.matcher(field);
        if (!matcher.find()) {
            return null;
        }
        return POSITIONS.getOrDefault(matcher.group(1), "запис") + " " + (Integer.parseInt(matcher.group(2)) + 1);
    }
}
