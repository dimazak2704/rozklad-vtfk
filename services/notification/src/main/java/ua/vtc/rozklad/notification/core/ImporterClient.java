package ua.vtc.rozklad.notification.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;

/** importer: AI parsing of a teacher's free-text absence message (CONTRACTS §5). */
public interface ImporterClient {

    AbsenceParse parseAbsence(String text, UUID teacherId, LocalDate today);

    /**
     * {@code POST /api/import/ai/parse-absence} result. {@code pairs == null} means the whole day;
     * {@code clarification} is set when the text is ambiguous.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AbsenceParse(LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                        AbsenceReasonCategory reasonCategory, Double confidence, String clarification) {
    }
}
