package ua.vtc.rozklad.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/** {@code ?page&size&sort} parsing: explicit 400s instead of silent clamping, whitelisted sort fields. */
class PageQueryTest {

    private final PageQueryArgumentResolver resolver = new PageQueryArgumentResolver();

    @Test
    void defaultsAndWhitelistedSort() throws Exception {
        PageQuery query = resolve(request("sort", "name,desc", "sort", "code"));

        Pageable pageable = query.toPageable(Map.of("name", "name", "code", "code"), Sort.by("code"));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("name"), Sort.Order.asc("code"),
                Sort.Order.asc("id"));
    }

    @Test
    void defaultSortAppliesWhenNoneIsRequested() throws Exception {
        Pageable pageable = resolve(request("page", "2", "size", "50")).toPageable(Map.of(), Sort.by("code"));

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("code"), Sort.Order.asc("id"));
    }

    @Test
    void invalidValuesAreUkrainianFieldErrors() {
        assertThatThrownBy(() -> resolve(request("page", "-1", "size", "500", "sort", "name,sideways")))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("page", "size", "sort"));
        assertThatThrownBy(() -> resolve(request("page", "abc")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void unknownSortFieldIsRejected() throws Exception {
        PageQuery query = resolve(request("sort", "password"));

        assertThatThrownBy(() -> query.toPageable(Map.of("name", "name"), Sort.by("name")))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .singleElement().satisfies(field -> assertThat(field.message()).contains("«password»", "name")));
    }

    private PageQuery resolve(MockHttpServletRequest request) throws Exception {
        MethodParameter parameter = new MethodParameter(PageQueryTest.class.getDeclaredMethod("handler", PageQuery.class), 0);
        return (PageQuery) resolver.resolveArgument(parameter, null, new ServletWebRequest(request), null);
    }

    private static MockHttpServletRequest request(String... pairs) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i < pairs.length; i += 2) {
            request.addParameter(pairs[i], pairs[i + 1]);
        }
        return request;
    }

    /** A controller method signature for {@link MethodParameter}. */
    @SuppressWarnings("unused")
    private void handler(PageQuery query) {
        // лише сигнатура для MethodParameter
    }
}
