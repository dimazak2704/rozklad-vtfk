package ua.vtc.rozklad.core.catalog.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultType;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogService;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** Anonymous catalog endpoints: no token needed, parameters validated. */
@WebMvcTest(PublicCatalogController.class)
@Import(WebSliceTestConfiguration.class)
class PublicCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private PublicCatalogService catalog;

    @Test
    void searchNeedsNoTokenAndReturnsResults() throws Exception {
        UUID id = UUID.randomUUID();
        given(catalog.search("4ki1", 10)).willReturn(List.of(
                new SearchResultDto(SearchResultType.GROUP, id, "4КІ1", "4 курс", "/g/4%D0%9A%D0%861")));

        mockMvc.perform(get("/api/public/search").param("q", "4ki1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("GROUP"))
                .andExpect(jsonPath("$[0].label").value("4КІ1"))
                .andExpect(jsonPath("$[0].url").value("/g/4%D0%9A%D0%861"));
    }

    @Test
    void searchLimitIsBounded() throws Exception {
        mockMvc.perform(get("/api/public/search").param("q", "к").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].message").value("має бути менше або рівне 50"));
    }

    @Test
    void listsAreOpen() throws Exception {
        given(catalog.groups()).willReturn(List.of());

        mockMvc.perform(get("/api/public/groups")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
