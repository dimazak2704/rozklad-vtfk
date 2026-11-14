package ua.vtc.rozklad.core.shared.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ua.vtc.rozklad.core.shared.security.TokenRoles;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(EventStreamController.class)
@Import({WebSliceTestConfiguration.class, SseBroadcaster.class})
class EventStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseBroadcaster broadcaster;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void anonymousClientsCannotSubscribe() throws Exception {
        mockMvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceAccountsCannotSubscribe() throws Exception {
        mockMvc.perform(get("/api/events/stream").with(TestJwts.service()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminReceivesRoleAndPersonalEventsButNotOthersOnes() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/events/stream").with(TestJwts.admin())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        broadcaster.sendToRole("ADMIN", SseEventTypes.CHANGE_UPDATED, Map.of("id", "change-1", "status", "CONFIRMED"));
        broadcaster.sendToUser(TestJwts.ADMIN_ID, SseEventTypes.GENERATION_PROGRESS, Map.of("hard", 0));
        broadcaster.sendToRole("TEACHER", SseEventTypes.SCHEDULE_CHANGED, Map.of("for", "teachers"));
        broadcaster.sendToUser(TestJwts.KRENTSIN_ID, SseEventTypes.CHANGE_UPDATED, Map.of("for", "krentsin"));

        String stream = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(stream).contains("event:ping", "retry:5000");
        assertThat(stream).contains("event:change.updated", "\"status\":\"CONFIRMED\"");
        assertThat(stream).contains("event:generation.progress");
        assertThat(stream).doesNotContain("teachers", "krentsin");
    }

    @Test
    void aStreamLastsOnlyAsLongAsItsAccessToken() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(120);

        MvcResult result = mockMvc.perform(get("/api/events/stream").with(teacherToken(UUID.randomUUID(), expiresAt))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // сервер сам закриває потік, коли токен спливає: клієнт перепідключається зі свіжим токеном,
        // а відкликаний обліковий запис нового не отримає
        assertThat(result.getRequest().getAsyncContext().getTimeout()).isBetween(100_000L, 120_000L);
    }

    @Test
    void theEleventhStreamOfOneAccountIsRefusedUntilOneCloses() throws Exception {
        JwtRequestPostProcessor token = teacherToken(UUID.randomUUID(), Instant.now().plusSeconds(300));
        List<MvcResult> open = new ArrayList<>();
        for (int tab = 0; tab < SseBroadcaster.MAX_STREAMS_PER_USER; tab++) {
            open.add(mockMvc.perform(get("/api/events/stream").with(token).accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn());
        }

        mockMvc.perform(get("/api/events/stream").with(token).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:too-many-requests"))
                .andExpect(jsonPath("$.requestId").exists());
        mockMvc.perform(get("/api/events/stream").with(TestJwts.admin()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        open.getFirst().getRequest().getAsyncContext().complete();

        mockMvc.perform(get("/api/events/stream").with(token).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @Test
    void broadcastReachesEverySubscriber() throws Exception {
        MvcResult admin = mockMvc.perform(get("/api/events/stream").with(TestJwts.admin())).andReturn();
        MvcResult teacher = mockMvc.perform(get("/api/events/stream").with(TestJwts.teacher())).andReturn();

        broadcaster.broadcast(SseEventTypes.ORDER_PUBLISHED, Map.of("number", 35));

        assertThat(admin.getResponse().getContentAsString()).contains("event:order.published", "\"number\":35");
        assertThat(teacher.getResponse().getContentAsString()).contains("event:order.published");
    }

    private static JwtRequestPostProcessor teacherToken(UUID id, Instant expiresAt) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id.toString())
                        .claim("preferred_username", "teacher-" + id)
                        .claim("roles", List.of("TEACHER"))
                        .expiresAt(expiresAt))
                .authorities(TokenRoles::authorities);
    }
}
