package ua.vtc.rozklad.importer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** A query value Tomcat cannot decode as UTF-8 is a 400 naming the parameter, not a 500. */
class UndecodableParameterTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void undecodableParameterIsAValidationErrorOfThatParameter() {
        ResponseEntity<Object> response = handler.handleUndecodableParameter(new InvalidParameterException(
                "Character decoding failed. Parameter [status] with value [�] has been ignored.", 400),
                new MockHttpServletRequest("GET", "/api/x"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getType().toString()).isEqualTo("urn:rozklad:problem:validation");
        assertThat((List<?>) body.getProperties().get("errors")).singleElement()
                .isEqualTo(new ApiExceptionHandler.FieldErrorDto("status",
                        "Значення не вдалося розкодувати: очікується текст у кодуванні UTF-8."));
    }

    @Test
    void messageWithoutAParameterNameIsABadRequest() {
        ResponseEntity<Object> response = handler.handleUndecodableParameter(
                new InvalidParameterException("broken", 400), new MockHttpServletRequest("GET", "/api/x"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getType().toString())
                .isEqualTo("urn:rozklad:problem:bad-request");
    }
}
