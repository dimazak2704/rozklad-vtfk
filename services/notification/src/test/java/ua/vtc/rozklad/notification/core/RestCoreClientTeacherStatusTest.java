package ua.vtc.rozklad.notification.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;
import ua.vtc.rozklad.notification.core.CoreClient.TeacherStatus;

/** {@code GET /api/internal/teachers/{id}} read as «still active?» for the nightly offboarding check. */
class RestCoreClientTeacherStatusTest {

    private static final UUID TEACHER = UUID.fromString("0190f0a0-0000-7000-8000-000000000001");
    private static final String URL = "http://core:8081/api/internal/teachers/" + TEACHER;

    private final RestClient.Builder service = RestClient.builder().baseUrl("http://core:8081");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(service).build();
    private final RestCoreClient client = new RestCoreClient(service.build(), RestClient.builder(),
            new ServiceUrlsProperties(URI.create("http://core:8081"), URI.create("http://importer:8083"),
                    URI.create("http://localhost:5180")));

    @Test
    void activeInactiveAndUnknownTeachers() {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(true), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(body(false), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withResourceNotFound());

        assertThat(client.teacherStatus(TEACHER)).isEqualTo(TeacherStatus.ACTIVE);
        assertThat(client.teacherStatus(TEACHER)).isEqualTo(TeacherStatus.INACTIVE);
        assertThat(client.teacherStatus(TEACHER)).isEqualTo(TeacherStatus.GONE);
        server.verify();
    }

    @Test
    void coreFailureIsNotTakenForAnAnswer() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.teacherStatus(TEACHER)).isInstanceOf(UpstreamUnavailableException.class);
    }

    private static String body(boolean active) {
        return """
                {"id":"%s","lastName":"Непийвода","firstName":"Марина","shortName":"Непийвода М.В.",
                 "cycleCommission":"КІ","active":%s,"accountId":null,"linked":false}""".formatted(TEACHER, active);
    }
}
