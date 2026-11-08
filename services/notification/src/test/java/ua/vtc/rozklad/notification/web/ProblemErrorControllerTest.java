package ua.vtc.rozklad.notification.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Container errors (Tomcat refuses TRACE with 405) are ProblemDetail with a type and the request id. */
class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController();

    @Test
    void refusedTraceIsAMethodNotAllowedProblem() {
        MockHttpServletRequest request = new MockHttpServletRequest("TRACE", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 405);
        request.addHeader("X-Request-Id", "abc-123");

        ResponseEntity<ProblemDetail> response = controller.error(request, new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getType().toString()).isEqualTo("urn:rozklad:problem:method-not-allowed");
        assertThat(response.getBody().getProperties()).containsEntry("requestId", "abc-123");
    }

    @Test
    void unknownStatusIsAnInternalProblem() {
        ResponseEntity<ProblemDetail> response = controller.error(new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getType().toString()).isEqualTo("urn:rozklad:problem:internal");
        assertThat(response.getBody().getProperties()).containsKey("requestId");
    }
}
