package ua.vtc.rozklad.notification.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ChangeSummary;
import ua.vtc.rozklad.notification.core.CoreDtos.CurrentUser;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreDtos.Schedule;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;

/** {@link CoreClient} over HTTP (CONTRACTS §1, §3, §4). */
@Component
public class RestCoreClient implements CoreClient {

    private static final String CORE = "core";

    /** Public API and token relay: without the service token (works even when Keycloak is down). */
    private final RestClient publicClient;
    /** Internal API: client-credentials token of rozklad-notification. */
    private final RestClient serviceClient;

    public RestCoreClient(@Qualifier("coreRestClient") RestClient coreRestClient, RestClient.Builder builder,
                          ServiceUrlsProperties urls) {
        this.serviceClient = coreRestClient;
        this.publicClient = builder.clone().baseUrl(urls.coreUrl().toString()).build();
    }

    @Override
    public PublicMeta meta() {
        return UpstreamCalls.call(CORE, "meta", () -> publicClient.get().uri("/api/public/meta")
                .retrieve().body(PublicMeta.class));
    }

    @Override
    public List<PublicGroup> groups() {
        return UpstreamCalls.call(CORE, "groups", () -> publicClient.get().uri("/api/public/groups")
                .retrieve().body(new ParameterizedTypeReference<List<PublicGroup>>() {
                }));
    }

    @Override
    public List<PublicTeacher> teachers() {
        return UpstreamCalls.call(CORE, "teachers", () -> publicClient.get().uri("/api/public/teachers")
                .retrieve().body(new ParameterizedTypeReference<List<PublicTeacher>>() {
                }));
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        return UpstreamCalls.call(CORE, "search", () -> publicClient.get()
                .uri("/api/public/search?q={q}&limit={limit}", Map.of("q", query, "limit", limit))
                .retrieve().body(new ParameterizedTypeReference<List<SearchHit>>() {
                }));
    }

    @Override
    public BellSchedule bells(LocalDate date) {
        return UpstreamCalls.call(CORE, "bells", () -> publicClient.get()
                .uri("/api/public/bells?date={date}", date)
                .retrieve().body(BellSchedule.class));
    }

    @Override
    public Schedule schedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        return UpstreamCalls.call(CORE, "schedule", () -> publicClient.get()
                .uri("/api/public/schedule/{type}/{id}?from={from}&to={to}", type.path(), ownerId, from, to)
                .retrieve().body(Schedule.class));
    }

    @Override
    public CurrentUser currentUser(String bearerToken) {
        return UpstreamCalls.call(CORE, "me", () -> publicClient.get().uri("/api/me")
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve().body(CurrentUser.class));
    }

    @Override
    public ChangeSummary replyToChange(UUID changeId, UUID teacherId, ConfirmationDecision decision,
                                       String comment) {
        record ReplyRequest(UUID teacherId, ConfirmationDecision decision, String comment) {
        }
        return UpstreamCalls.call(CORE, "change reply", () -> serviceClient.post()
                .uri("/api/internal/changes/{id}/reply", changeId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReplyRequest(teacherId, decision, comment))
                .retrieve().body(ChangeSummary.class));
    }

    @Override
    public void reportAbsence(AbsenceReport report) {
        UpstreamCalls.run(CORE, "absence", () -> serviceClient.post()
                .uri("/api/internal/absences")
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
                .retrieve().toBodilessEntity());
    }
}
