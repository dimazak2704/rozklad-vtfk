package ua.vtc.rozklad.importer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A PostgreSQL «data exception» (SQLState class 22, e.g. 22P05 for NUL in a JSON column) is an
 * invalid value (400 bad-request), not «the record already exists» (409); a real constraint
 * violation stays 409.
 */
class DataExceptionMappingTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void dataExceptionIsABadRequest() {
        ResponseEntity<Object> response = handler.handleDataIntegrity(new DataIntegrityViolationException("write",
                new SQLException("unsupported Unicode escape sequence", "22P05")), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getType().toString())
                .isEqualTo("urn:rozklad:problem:bad-request");
    }

    @Test
    void uniqueViolationStaysAConflict() {
        ResponseEntity<Object> response = handler.handleDataIntegrity(new DataIntegrityViolationException("write",
                new SQLException("duplicate key", "23505")), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((ProblemDetail) response.getBody()).getType().toString())
                .isEqualTo("urn:rozklad:problem:data-integrity");
    }
}
