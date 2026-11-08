package ua.vtc.rozklad.core.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;

/**
 * Row values never reach exception messages and therefore logs (ARCHITECTURE §10): PostgreSQL's
 * «Detail: Key (name)=(…)» and the parameters pgjdbc inlines into «Batch entry 0 insert …» are
 * switched off ({@code logServerErrorDetail=false}), and the handler logs only SQLState and constraint.
 */
@CoreIntegrationTest
class DatabaseErrorPrivacyIT {

    private static final String SECRET = "Таємна назва 42";
    private static final String INSERT = "INSERT INTO department (id, name, created_at, updated_at) VALUES (?, ?, now(), now())";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM department WHERE name = ?", SECRET);
    }

    @Test
    void uniqueViolationDoesNotCarryTheRowValues() {
        jdbc.update(INSERT, UUID.randomUUID(), SECRET);

        DataIntegrityViolationException single = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbc.update(INSERT, UUID.randomUUID(), SECRET));
        DataIntegrityViolationException batch = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbc.batchUpdate(INSERT, List.<Object[]>of(new Object[] {UUID.randomUUID(), SECRET})));

        assertThat(messages(single)).isNotEmpty().noneMatch(message -> message.contains(SECRET));
        assertThat(messages(batch)).isNotEmpty().noneMatch(message -> message.contains(SECRET));
        assertThat(ApiExceptionHandler.describe(single)).isEqualTo("SQLState 23505").doesNotContain(SECRET);
    }

    /** A value out of the column's range (SQLState 22008) is not «the record already exists». */
    @Test
    void outOfRangeValueIsRecognizedAsADataException() {
        DataIntegrityViolationException outOfRange = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbc.queryForObject("SELECT CAST(? AS date) + 1000000000", Object.class, "5874897-12-31"));
        DataIntegrityViolationException duplicate = catchThrowableOfType(DataIntegrityViolationException.class, () -> {
            jdbc.update(INSERT, UUID.randomUUID(), SECRET);
            jdbc.update(INSERT, UUID.randomUUID(), SECRET);
        });

        assertThat(ApiExceptionHandler.isDataException(outOfRange)).isTrue();
        assertThat(ApiExceptionHandler.isDataException(duplicate)).isFalse();
    }

    private static List<String> messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
            if (cause instanceof SQLException sql) {
                for (SQLException next = sql.getNextException(); next != null; next = next.getNextException()) {
                    messages.add(String.valueOf(next.getMessage()));
                }
            }
        }
        return messages;
    }
}
