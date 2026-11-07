package ua.vtc.rozklad.importer.absence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;

/**
 * CONTRACTS §5 parse-absence answer: {@code pairs} {@code null} = the whole day; {@code confidence}
 * 0..1; {@code clarification} — a short Ukrainian question for the teacher when something is
 * missing. {@code source} (additive) tells whether rules or AI produced it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedAbsence(LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs, AbsenceReasonCategory reasonCategory,
                            double confidence, String clarification, Source source) {

    public enum Source {
        RULES, AI
    }

    public ParsedAbsence {
        pairs = pairs == null ? null : List.copyOf(pairs);
        confidence = Math.round(Math.max(0, Math.min(1, confidence)) * 100) / 100.0;
    }
}
