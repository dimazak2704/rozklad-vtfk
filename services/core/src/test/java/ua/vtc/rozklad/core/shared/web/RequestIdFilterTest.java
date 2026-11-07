package ua.vtc.rozklad.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void keepsSafeIncomingId() {
        assertThat(RequestIdFilter.resolve("gw-1f2e3d.42")).isEqualTo("gw-1f2e3d.42");
    }

    @Test
    void replacesMissingOrUnsafeIds() {
        assertThat(UUID.fromString(RequestIdFilter.resolve(null))).isNotNull();
        assertThat(RequestIdFilter.resolve("evil\nINFO forged log line")).doesNotContain("\n").hasSize(36);
        assertThat(RequestIdFilter.resolve("x".repeat(65))).hasSize(36);
    }

    @Test
    void exposesIdInMdcDuringRequestAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/meta");
        request.addHeader(RequestIdFilter.HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = MDC.get(RequestIdFilter.MDC_KEY);
            }
        });

        assertThat(seen[0]).isEqualTo("req-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatedIdIsReusedWhenTheRequestPassesTheFilterAgain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events/stream");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, new MockFilterChain());
        String generated = first.getHeader(RequestIdFilter.HEADER);

        String[] seen = new String[1];
        MockHttpServletRequest redispatched = request;
        redispatched.removeAttribute(RequestIdFilter.class.getName() + ".FILTERED");
        filter.doFilter(redispatched, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = MDC.get(RequestIdFilter.MDC_KEY);
            }
        });

        assertThat(seen[0]).isEqualTo(generated);
    }
}
