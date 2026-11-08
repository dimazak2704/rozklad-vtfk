package ua.vtc.rozklad.importer.absence;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.importer.ai.AiBudgetExceededException;
import ua.vtc.rozklad.importer.ai.AiExtractionException;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiExtractions.AbsenceExtraction;
import ua.vtc.rozklad.importer.ai.AiUnavailableException;

/**
 * Rules first; the text model is asked only when the rules are unsure (confidence below
 * {@value #AI_THRESHOLD}), the rules did not settle the message (a shape the answer cannot carry,
 * where only the teacher can clarify) and AI is available. AI problems never fail the call — the bot then gets
 * the rule-based answer with its clarification. The message text is never logged (absence reasons
 * are private).
 */
@Service
public class ParseAbsenceService {

    static final double AI_THRESHOLD = 0.7;
    private static final int MAX_DAYS_AHEAD = 366;

    private static final Logger log = LoggerFactory.getLogger(ParseAbsenceService.class);

    private final AbsenceTextParser rules;
    private final AiExtractionService ai;

    public ParseAbsenceService(AbsenceTextParser rules, AiExtractionService ai) {
        this.rules = rules;
        this.ai = ai;
    }

    public ParsedAbsence parse(String text, LocalDate today) {
        AbsenceTextParser.Reading reading = rules.read(text, today);
        ParsedAbsence byRules = reading.parsed();
        if (reading.settled() || byRules.confidence() >= AI_THRESHOLD || !ai.available()) {
            return byRules;
        }
        try {
            ParsedAbsence byAi = validated(ai.parseAbsence(text, today).value(), today);
            return byAi != null && byAi.confidence() > byRules.confidence() ? byAi : byRules;
        } catch (AiUnavailableException | AiExtractionException | AiBudgetExceededException e) {
            log.info("Absence text: AI fallback skipped ({}), rule-based answer returned", e.getClass().getSimpleName());
            return byRules;
        }
    }

    /** The model's answer within sane limits, or {@code null} when it is unusable. */
    static ParsedAbsence validated(AbsenceExtraction answer, LocalDate today) {
        if (answer.dateFrom() == null || answer.dateTo() == null || answer.dateTo().isBefore(answer.dateFrom())
                || answer.dateFrom().isBefore(today.minusDays(MAX_DAYS_AHEAD))
                || answer.dateTo().isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            return null;
        }
        List<Integer> pairs = null;
        if (answer.pairs() != null && !answer.pairs().isEmpty()) {
            TreeSet<Integer> valid = new TreeSet<>();
            for (Integer pair : answer.pairs()) {
                if (pair == null || pair < 1 || pair > 8) {
                    return null;
                }
                valid.add(pair);
            }
            pairs = List.copyOf(valid);
        }
        AbsenceReasonCategory reason;
        try {
            reason = AbsenceReasonCategory.valueOf(answer.reasonCategory());
        } catch (IllegalArgumentException | NullPointerException e) {
            reason = AbsenceReasonCategory.OTHER;
        }
        String clarification = answer.clarification() == null || answer.clarification().isBlank()
                ? null : answer.clarification().strip();
        return new ParsedAbsence(answer.dateFrom(), answer.dateTo(), pairs, reason, answer.confidence(), clarification,
                ParsedAbsence.Source.AI);
    }
}
