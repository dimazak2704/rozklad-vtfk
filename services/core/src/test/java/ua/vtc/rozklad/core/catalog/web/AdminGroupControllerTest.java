package ua.vtc.rozklad.core.catalog.web;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.catalog.internal.GroupDtos.GroupDto;
import ua.vtc.rozklad.core.catalog.internal.GroupService;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.web.Ref;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** HTTP contract of {@code /api/admin/groups}: validation, statuses, security. */
@WebMvcTest(AdminGroupController.class)
@Import(WebSliceTestConfiguration.class)
class AdminGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private GroupService groups;

    @Test
    void invalidBodyIs400WithUkrainianFieldMessages() throws Exception {
        mockMvc.perform(post("/api/admin/groups").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" \",\"course\":9,\"size\":-1,\"shift\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message").value("не може бути пустим"))
                .andExpect(jsonPath("$.errors[1].field").value("course"))
                .andExpect(jsonPath("$.errors[1].message").value("має бути менше або рівне 6"))
                .andExpect(jsonPath("$.errors[2].field").value("shift"))
                .andExpect(jsonPath("$.errors[3].field").value("size"))
                .andExpect(jsonPath("$.errors[3].message").value("має бути більше або рівне 0"));
    }

    @Test
    void createdGroupIs201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        given(groups.create(any())).willReturn(new GroupDto(id, "4КІ1", 4, UUID.randomUUID(),
                new Ref(UUID.randomUUID(), "КІ"), 1, 25, 1, null, null, true, List.of(), 0));

        mockMvc.perform(post("/api/admin/groups").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"4ki1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/admin/groups/" + id)))
                .andExpect(jsonPath("$.code").value("4КІ1"))
                .andExpect(jsonPath("$.specialty.label").value("КІ"));
    }

    @Test
    void unknownGroupIs404() throws Exception {
        UUID id = UUID.randomUUID();
        given(groups.get(id)).willThrow(NotFoundException.of("Групу", id));

        mockMvc.perform(get("/api/admin/groups/{id}", id).with(TestJwts.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Групу з ідентифікатором " + id + " не знайдено"));
    }

    @Test
    void referencedGroupCannotBeDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new ConflictException("Групу «4КІ1» не можна видалити")).given(groups).delete(id);

        mockMvc.perform(delete("/api/admin/groups/{id}", id).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:conflict"))
                .andExpect(jsonPath("$.detail").value("Групу «4КІ1» не можна видалити"));
    }

    @Test
    void malformedPagingIs400() throws Exception {
        mockMvc.perform(get("/api/admin/groups").param("size", "1000").param("sort", "code,up").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[1].field").value("sort"));
    }

    @Test
    void anonymousIs401AndTeacherIs403() throws Exception {
        mockMvc.perform(get("/api/admin/groups")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/groups").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }
}
