package ua.vtc.rozklad.notification.core;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link ImporterClient} over HTTP with the service-account token. */
@Component
public class RestImporterClient implements ImporterClient {

    private final RestClient importer;

    public RestImporterClient(@Qualifier("importerRestClient") RestClient importerRestClient) {
        this.importer = importerRestClient;
    }

    @Override
    public AbsenceParse parseAbsence(String text, UUID teacherId, LocalDate today) {
        record ParseRequest(String text, UUID teacherId, LocalDate today) {
        }
        return UpstreamCalls.call("importer", "parse-absence", () -> importer.post()
                .uri("/api/import/ai/parse-absence")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ParseRequest(text, teacherId, today))
                .retrieve().body(AbsenceParse.class));
    }
}
