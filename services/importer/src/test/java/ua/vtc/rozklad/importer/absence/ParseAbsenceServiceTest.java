package ua.vtc.rozklad.importer.absence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.importer.ai.AiBudgetExceededException;
import ua.vtc.rozklad.importer.ai.AiCallInfo;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiExtractions.AbsenceExtraction;
import ua.vtc.rozklad.importer.ai.AiResult;

class ParseAbsenceServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final AiCallInfo CALL = new AiCallInfo("claude-haiku-5-5", 300, 60, new BigDecimal("0.000060"));

    private final AiExtractionService ai = mock(AiExtractionService.class);
    private final ParseAbsenceService service = new ParseAbsenceService(new AbsenceTextParser(), ai);

    @Test
    void confidentRulesDoNotCallAi() {
        when(ai.available()).thenReturn(true);

        ParsedAbsence parsed = service.parse("4.11 не буду, лікарняний", TODAY);

        assertThat(parsed.source()).isEqualTo(ParsedAbsence.Source.RULES);
        verify(ai, never()).parseAbsence(anyString(), any());
    }

    @Test
    void unsureRulesAskAiAndKeepItsBetterAnswer() {
        when(ai.available()).thenReturn(true);
        when(ai.parseAbsence(anyString(), any())).thenReturn(new AiResult<>(new AbsenceExtraction(
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9), List.of(3, 2, 3), "PERSONAL", 0.85, " "), CALL));

        ParsedAbsence parsed = service.parse("мене не буде на третій і другій", TODAY);

        assertThat(parsed.source()).isEqualTo(ParsedAbsence.Source.AI);
        assertThat(parsed.pairs()).containsExactly(2, 3);
        assertThat(parsed.reasonCategory()).isEqualTo(AbsenceReasonCategory.PERSONAL);
        assertThat(parsed.clarification()).isNull();
    }

    @Test
    void invalidAiAnswerFallsBackToRules() {
        when(ai.available()).thenReturn(true);
        when(ai.parseAbsence(anyString(), any())).thenReturn(new AiResult<>(new AbsenceExtraction(
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 8), null, "SICK_LEAVE", 0.95, null), CALL));

        assertThat(service.parse("не зможу провести пари", TODAY).source()).isEqualTo(ParsedAbsence.Source.RULES);
    }

    @Test
    void exhaustedBudgetOrDisabledAiStillAnswersByRules() {
        when(ai.available()).thenReturn(true);
        when(ai.parseAbsence(anyString(), any()))
                .thenThrow(new AiBudgetExceededException(new BigDecimal("2.5"), new BigDecimal("2.0")));
        ParsedAbsence parsed = service.parse("не зможу провести пари", TODAY);
        assertThat(parsed.source()).isEqualTo(ParsedAbsence.Source.RULES);
        assertThat(parsed.clarification()).isNotBlank();

        when(ai.available()).thenReturn(false);
        assertThat(service.parse("не зможу провести пари", TODAY).source()).isEqualTo(ParsedAbsence.Source.RULES);
    }

    @Test
    void pairsOutsideTheDayMakeTheAiAnswerUnusable() {
        assertThat(ParseAbsenceService.validated(new AbsenceExtraction(TODAY, TODAY, List.of(9), "OTHER", 0.9, null), TODAY))
                .isNull();
        assertThat(ParseAbsenceService.validated(new AbsenceExtraction(TODAY, TODAY, List.of(1), "UNKNOWN", 0.9, null), TODAY))
                .extracting(ParsedAbsence::reasonCategory).isEqualTo(AbsenceReasonCategory.OTHER);
    }
}
