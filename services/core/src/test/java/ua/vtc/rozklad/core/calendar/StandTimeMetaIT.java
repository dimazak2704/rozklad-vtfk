package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.shared.time.StandClock;
import ua.vtc.rozklad.core.support.ConsumerQueuesConfiguration;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.TestcontainersConfiguration;

/**
 * Core started with a stand time (Friday 20.11.2026 10:00 in Kyiv) and without the test clock: the
 * public meta reports the stand day, its week and pair, and says that the time is shifted, while
 * the outbox keeps real time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.stand-time.at=2026-11-20T10:00")
@Import({TestcontainersConfiguration.class, ConsumerQueuesConfiguration.class})
class StandTimeMetaIT {

    private static final LocalDate STAND_DAY = LocalDate.of(2026, 11, 20);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalendarQueries calendar;

    @Autowired
    private Clock clock;

    @Autowired
    private EventPublicationRegistry outbox;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void publicMetaReportsTheStandDayWeekAndPair() throws Exception {
        mvc.perform(post("/api/admin/semesters").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academicYear":"2026/2027","number":1,"startsOn":"2026-09-01","endsOn":"2026-12-30"}"""))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get("/api/public/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(STAND_DAY.toString()))
                .andExpect(jsonPath("$.studyDay").value(true))
                .andExpect(jsonPath("$.weekNumber").value(calendar.weekNumber(STAND_DAY)))
                .andExpect(jsonPath("$.parity").value(calendar.parity(STAND_DAY).name()))
                .andExpect(jsonPath("$.currentPair.number").value(2))
                .andExpect(jsonPath("$.nextStudyDay.date").value("2026-11-23"))
                .andExpect(jsonPath("$.standTime").value(true))
                .andReturn();

        Instant now = Instant.parse(ApiTestSupport.<String>read(result, "$.now"));
        assertThat(now).isBetween(Instant.parse("2026-11-20T08:00:00Z"), Instant.parse("2026-11-20T08:15:00Z"));
    }

    @Test
    void theDomainClockIsShiftedButTheOutboxKeepsRealTime() {
        assertThat(StandClock.isShifted(clock)).isTrue();
        Clock outboxClock = (Clock) ReflectionTestUtils.getField(outbox, "clock");

        assertThat(StandClock.isShifted(outboxClock)).isFalse();
        assertThat(Duration.between(Clock.systemUTC().instant(), outboxClock.instant()).abs())
                .isLessThan(Duration.ofMinutes(1));
    }
}
