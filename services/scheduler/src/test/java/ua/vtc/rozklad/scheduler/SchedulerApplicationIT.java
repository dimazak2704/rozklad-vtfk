package ua.vtc.rozklad.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.timefold.solver.core.api.score.stream.ConstraintMetaModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.scheduler.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.SchedulerIntegrationTest;

/** Smoke test: the scheduler boots against real PostgreSQL and RabbitMQ with its security rules. */
@SchedulerIntegrationTest
class SchedulerApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ConstraintMetaModel constraintMetaModel;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void schedulerApiRequiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/scheduler/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.title").value("Потрібна автентифікація"));

        mockMvc.perform(get("/api/scheduler/status").with(jwt()
                        .jwt(token -> token.claim("realm_access", Map.of("roles", List.of("TEACHER"))))
                        .authorities(new KeycloakRealmRoleConverter())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Доступ заборонено"));
    }

    @Test
    void generationQueueAndDeadLetterQueueAreDeclared() {
        assertThat(amqpAdmin.getQueueInfo(RozkladEvents.Queues.SCHEDULER_GENERATION)).isNotNull();
        assertThat(amqpAdmin.getQueueInfo(RozkladEvents.Queues.deadLetterQueue(
                RozkladEvents.Queues.SCHEDULER_GENERATION))).isNotNull();
    }

    @Test
    void solverKnowsExactlyTheDocumentedConstraints() {
        assertThat(constraintMetaModel.getConstraints())
                .extracting(constraint -> TimetableConstraint.byId(constraint.getConstraintRef().id()))
                .containsExactlyInAnyOrder(TimetableConstraint.values());
    }
}
