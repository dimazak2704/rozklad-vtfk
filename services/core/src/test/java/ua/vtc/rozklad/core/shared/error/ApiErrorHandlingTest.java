package ua.vtc.rozklad.core.shared.error;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** ProblemDetail format, Ukrainian messages and path security of the core API. */
@WebMvcTest(ProbeController.class)
@Import(WebSliceTestConfiguration.class)
@ActiveProfiles(ProbeController.PROFILE)
class ApiErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void validationErrorsListFieldsWithUkrainianMessages() throws Exception {
        mockMvc.perform(post("/api/admin/probe/groups").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" \",\"course\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.title").value("Некоректні дані"))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message").value("не може бути пустим"))
                .andExpect(jsonPath("$.errors[1].field").value("course"))
                .andExpect(jsonPath("$.errors[1].message").value("має бути менше або рівне 4"));
    }

    @Test
    void invalidRequestParameterIsAValidationError() throws Exception {
        mockMvc.perform(get("/api/admin/probe/pairs").param("pair", "9").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pair"))
                .andExpect(jsonPath("$.errors[0].message").value("має бути менше або рівне 8"));
    }

    @Test
    void unknownResourceIsUkrainian404() throws Exception {
        mockMvc.perform(get("/api/admin/no-such-resource").with(TestJwts.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Не знайдено"))
                .andExpect(jsonPath("$.detail").value("Ресурс «api/admin/no-such-resource» не існує."));
    }

    @Test
    void malformedJsonIsAValidationProblemWithoutFields() throws Exception {
        mockMvc.perform(post("/api/admin/probe/groups").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.title").value("Некоректні дані"))
                .andExpect(jsonPath("$.detail").value("Тіло запиту не вдалося прочитати: перевірте формат JSON."))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void valueOfTheWrongFormatPointsAtItsField() throws Exception {
        expectFieldError("{\"reasonCategory\":\"HACK\"}", "reasonCategory", "Допустимі значення: SICK_LEAVE, OTHER.");
        expectFieldError("{\"dateFrom\":\"2026-13-40\"}", "dateFrom", "Очікується дата у форматі РРРР-ММ-ДД.");
        expectFieldError("{\"pairs\":\"8\"}", "pairs", "Очікується список.");
        expectFieldError("{\"pairs\":[1,\"x\"]}", "pairs[1]", "Очікується число.");
    }

    @Test
    void nulCharacterInTextIsAFieldErrorNotADatabaseConflict() throws Exception {
        expectFieldError("{\"comment\":\"x\\u0000\"}", "comment", "Текст містить недопустимий символ NUL (U+0000).");
    }

    @Test
    void invalidRowOfAListBodyKeepsItsIndex() throws Exception {
        mockMvc.perform(put("/api/admin/probe/grid").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"weekday\":1,\"pair\":1},{\"weekday\":7,\"pair\":9}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grid[1].pair"))
                .andExpect(jsonPath("$.errors[1].field").value("grid[1].weekday"));
    }

    @Test
    void queryParameterOfTheWrongFormatIsAFieldError() throws Exception {
        mockMvc.perform(get("/api/admin/probe/days").param("from", "abc").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.detail").value("Значення «abc» параметра «from» має неправильний формат."))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("Очікується дата у форматі РРРР-ММ-ДД."));
        mockMvc.perform(get("/api/admin/probe/days").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    void frameworkErrorsGetAStableType() throws Exception {
        mockMvc.perform(post("/api/admin/probe/days").with(TestJwts.admin()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:method-not-allowed"));
    }

    private void expectFieldError(String body, String field, String message) throws Exception {
        mockMvc.perform(post("/api/admin/probe/absences").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(message));
    }

    @Test
    void notFoundCarriesMessageAndRequestId() throws Exception {
        mockMvc.perform(get("/api/admin/probe/not-found").with(TestJwts.admin())
                        .header("X-Request-Id", "req-42"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req-42"))
                .andExpect(jsonPath("$.title").value("Не знайдено"))
                .andExpect(jsonPath("$.detail").value("Групу з ідентифікатором 4КІ9 не знайдено"))
                .andExpect(jsonPath("$.instance").value("/api/admin/probe/not-found"))
                .andExpect(jsonPath("$.requestId").value("req-42"));
    }

    @Test
    void scheduleConflictsAreListed() throws Exception {
        mockMvc.perform(get("/api/admin/probe/conflict").with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:schedule-conflict"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("TEACHER"))
                .andExpect(jsonPath("$.conflicts[0].severity").value("HARD"))
                .andExpect(jsonPath("$.conflicts[0].lessonIds[0]").value(ProbeController.LESSON_ID.toString()))
                .andExpect(jsonPath("$.conflicts[0].date").value("2026-10-19"))
                .andExpect(jsonPath("$.conflicts[0].pair").value(2))
                .andExpect(jsonPath("$.conflicts[0].weekday").doesNotExist());
    }

    @Test
    void businessRuleViolationIs422WithCode() throws Exception {
        mockMvc.perform(get("/api/admin/probe/business").with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PUBLISHED"))
                .andExpect(jsonPath("$.detail").value("Розпорядження вже опубліковане"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mockMvc.perform(get("/api/admin/probe/boom").with(TestJwts.admin()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Внутрішня помилка сервера"))
                .andExpect(jsonPath("$.detail", startsWith("Сталася непередбачена помилка")));
    }

    @Test
    void anonymousCallToAdminApiIs401ProblemWithBearerChallenge() throws Exception {
        mockMvc.perform(get("/api/admin/probe/not-found"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.title").value("Потрібна автентифікація"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void teacherCannotUseAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/probe/not-found").with(TestJwts.teacher()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Доступ заборонено"));
    }

    @Test
    void serviceAccountCannotUseTeacherApi() throws Exception {
        mockMvc.perform(get("/api/me/probe").with(TestJwts.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherSeesHisIdentity() throws Exception {
        mockMvc.perform(get("/api/me/probe").with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TestJwts.KRENTSIN_ID.toString()))
                .andExpect(jsonPath("$.username").value("krentsin"))
                .andExpect(jsonPath("$.roles[0]").value("TEACHER"));
    }

    @Test
    void publicApiNeedsNoTokenAndWritesTimesAsHoursAndMinutes() throws Exception {
        mockMvc.perform(get("/api/public/probe/bell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("08:30"))
                .andExpect(jsonPath("$.endsAt").value("09:30"));
    }

    @Test
    void pathsOutsideTheApiContractAreDenied() throws Exception {
        mockMvc.perform(get("/api/unknown").with(TestJwts.admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerTokenIsDecodedAndRealmRolesBecomeAuthorities() throws Exception {
        Jwt jwt = Jwt.withTokenValue("admin-token").header("alg", "RS256")
                .subject(TestJwts.ADMIN_ID.toString())
                .claim("preferred_username", "admin")
                .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .build();
        given(jwtDecoder.decode("admin-token")).willReturn(jwt);

        mockMvc.perform(get("/api/admin/probe/not-found").header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }
}
