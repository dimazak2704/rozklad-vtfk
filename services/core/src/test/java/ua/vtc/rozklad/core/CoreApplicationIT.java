package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;

/** Boots the whole core against real PostgreSQL, RabbitMQ and Redis. */
@CoreIntegrationTest
class CoreApplicationIT {

    @Autowired
    private RestTestClient client;

    @Autowired
    private Flyway flyway;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @LocalServerPort
    private int port;

    @Test
    void allMigrationsAreAppliedAndSchemaMatchesEntities() {
        // ddl-auto=validate already failed the context if an entity did not match the schema
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getVersion).map(Object::toString))
                .contains("1.001", "1.002", "1.100", "1.200", "1.300", "1.400", "1.500", "1.600", "1.800");
    }

    @Test
    void healthIsUpWithDatabaseBrokerAndCache() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void protectedApiAnswers401ProblemDetail() {
        client.get().uri("/api/admin/teachers").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.title").isEqualTo("Потрібна автентифікація");
    }

    /** Tomcat rejects an encoded slash or NUL before Spring: the answer is still a ProblemDetail, not HTML. */
    @Test
    void requestsRejectedByTomcatGetAProblemDetail() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String path : new String[] {"/api/public/groups/a%2Fb", "/api/public/groups/%00"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Request-Id", "r2-tomcat-test").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                    assertThat(type).startsWith("application/problem+json"));
            assertThat(response.body()).contains("\"type\":\"urn:rozklad:problem:bad-request\"")
                    .contains("\"requestId\":\"r2-tomcat-test\"");
        }
    }

    /** A query value that is not UTF-8 (Tomcat's InvalidParameterException) is a 400 naming the parameter. */
    @Test
    void undecodableQueryParameterIsAValidationProblem() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/public/search?q=%E0%A4")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"type\":\"urn:rozklad:problem:validation\"")
                .contains("\"field\":\"q\"");
    }

    @Test
    void openApiDescriptionIsPublished() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.info.title").isEqualTo("Розклад ВТФК — core API");
    }

    @Test
    void ownQueueAndDeadLetterQueueAreDeclared() {
        assertThat(amqpAdmin.getQueueInfo(RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)).isNotNull();
        assertThat(amqpAdmin.getQueueInfo("core.q.scheduler-results.dlq")).isNotNull();
    }
}
