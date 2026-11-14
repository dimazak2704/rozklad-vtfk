package ua.vtc.rozklad.core.order.internal.publicview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.internal.PublicHistoryTestConfiguration;
import ua.vtc.rozklad.core.support.TestClock;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(PublicChangesController.class)
@Import({WebSliceTestConfiguration.class, TestClock.Configuration.class, PublicHistoryTestConfiguration.class})
class PublicChangesControllerTest {

    /** Wednesday 21.10.2026, 10:00 in Kyiv: with 7 days of history visitors see 14.10 and later. */
    private static final Instant NOW = Instant.parse("2026-10-21T07:00:00Z");

    /** Spring Security's default for a response that sets no Cache-Control of its own (visitors, as before). */
    private static final String VISITOR_CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestClock clock;

    @MockitoBean
    private PublicChanges changes;

    @MockitoBean
    private SettingsService settings;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        clock.stopAt(NOW);
        given(settings.changesHistoryDays()).willReturn(7);
        given(changes.of(any())).willAnswer(call -> empty(call.getArgument(0)));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void anyoneSeesThePublishedChangesOfADate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 19);
        given(changes.of(date)).willReturn(new PublicChangesDto(date, Parity.DENOMINATOR, true, "Понеділок",
                new PublicChangesDto.PublishedOrder(UUID.randomUUID(), 35, Instant.parse("2026-10-18T14:00:00Z"), false),
                List.of(new PublicChangesDto.PublishedOrder(UUID.randomUUID(), 36, Instant.parse("2026-10-19T07:00:00Z"),
                        false)),
                List.of(new PublicChangesDto.GroupChanges(new PublicChangesDto.ChangeGroup(UUID.randomUUID(), "1ТК1", 1),
                        List.of(new PublicChangesDto.ChangeView("1", OrderRowType.CANCELLATION,
                                new PublicChangesDto.Side("Ін. мова", "Крисак Л. (п.)"), "Відп.",
                                new PublicChangesDto.Side("Пари не буде", null), null, "1 підгрупа", 36))))));

        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, VISITOR_CACHE_CONTROL))
                .andExpect(jsonPath("$.parity").value("DENOMINATOR"))
                .andExpect(jsonPath("$.studyDay").value(true))
                .andExpect(jsonPath("$.order.number").value(35))
                .andExpect(jsonPath("$.groups[0].group.code").value("1ТК1"))
                .andExpect(jsonPath("$.groups[0].items[0].to.discipline").value("Пари не буде"))
                .andExpect(jsonPath("$.groups[0].items[0].subgroupNote").value("1 підгрупа"))
                .andExpect(jsonPath("$.amendments[0].number").value(36))
                .andExpect(jsonPath("$.groups[0].items[0].orderNumber").value(36));
    }

    @Test
    void withoutADateTodayOfTheCoreClockIsShown() throws Exception {
        mockMvc.perform(get("/api/public/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-21"));
    }

    @Test
    void visitorSeesTheFirstDayOfTheWindowAndTheFuture() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-14"));
        mockMvc.perform(get("/api/public/changes").param("date", "2027-03-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2027-03-01"));
    }

    @Test
    void visitorAskingForTheDayBeforeTheWindowGetsHistoryNotPublic() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-13"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:history-not-public"))
                .andExpect(jsonPath("$.title").value("Архів недоступний"))
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_PUBLIC"))
                .andExpect(jsonPath("$.publicHistoryFrom").value("2026-10-14"))
                .andExpect(jsonPath("$.detail").value("Заміни за дні до 14.10.2026 бачить лише персонал коледжу."));
        verify(changes, never()).of(any());
    }

    /** Staff answers are private, as the schedule's: a shared cache must not hand an old day to a visitor. */
    @Test
    void staffSeeTheWholeArchiveAsAPrivateAnswer() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "2025-09-01").with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION))
                .andExpect(jsonPath("$.date").value("2025-09-01"));
        mockMvc.perform(get("/api/public/changes").param("date", "2025-09-01").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"));
        // і на сьогодні: відповідь персоналу та сама приватна, хоч вікно тут нічого не обмежує
        mockMvc.perform(get("/api/public/changes").with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"));
    }

    /** The bot asks for its teacher and admin chats and limits other chats itself. */
    @Test
    void theBotSeesTheWholeArchiveButTheImporterDoesNot() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "2025-09-01").with(TestJwts.service()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"));
        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-21").with(TestJwts.importerService()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, VISITOR_CACHE_CONTROL));
        mockMvc.perform(get("/api/public/changes").param("date", "2025-09-01").with(TestJwts.importerService()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_PUBLIC"));
    }

    /** A token the server cannot verify is refused as on every other path, not treated as a visitor. */
    @Test
    void forgedOrExpiredTokenIs401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("expired"));

        mockMvc.perform(get("/api/public/changes").param("date", "2025-09-01")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:unauthorized"));
        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-21")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andExpect(status().isUnauthorized());
        verify(changes, never()).of(any());
    }

    @Test
    void malformedDateIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "19.10.2026")).andExpect(status().isBadRequest());
    }

    private static PublicChangesDto empty(LocalDate date) {
        return new PublicChangesDto(date, Parity.NUMERATOR, true, "Середа", null, List.of(), List.of());
    }
}
