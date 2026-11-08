package ua.vtc.rozklad.importer.ai;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.ai.AiExtractions.AbsenceExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.CurriculumExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.OrderExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.ProgramHoursExtraction;
import ua.vtc.rozklad.importer.config.AiProperties;

/**
 * Typed AI extractions used by the import handlers and the absence parser. Every call: checks that
 * AI is available and the daily budget is not exhausted, checks and fits the documents
 * ({@link AiDocumentPreparer}: a damaged file fails before the paid call), picks the model (vision for scans, text
 * otherwise), sends the JSON schema, records usage (also for refusals, cut-offs and invalid JSON —
 * those tokens are billed) and parses the answer.
 */
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    private final AiExtractionPort port;
    private final AiUsageService usage;
    private final AiProperties properties;
    private final JsonMapper jsonMapper;

    public AiExtractionService(AiExtractionPort port, AiUsageService usage, AiProperties properties, JsonMapper jsonMapper) {
        this.port = port;
        this.usage = usage;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public boolean available() {
        return port.available();
    }

    /**
     * Fails fast before accepting work that needs AI.
     *
     * @throws AiUnavailableException     when AI is not configured
     * @throws AiBudgetExceededException  when today's budget is spent
     */
    public void ensureCanCall() {
        if (!port.available()) {
            throw new AiUnavailableException(DisabledAiAdapter.MESSAGE);
        }
        usage.ensureWithinBudget();
    }

    public AiResult<OrderExtraction> extractOrder(List<AiDocument> pages, UUID jobId) {
        return call(AiPurpose.ORDER_DOCUMENT, pages, AiPrompts.ORDER, AiSchemas.order(), OrderExtraction.class, jobId);
    }

    /** From a scan/photo ({@code pages}) or from a table converted to text ({@code tableText}). */
    public AiResult<CurriculumExtraction> extractCurriculum(List<AiDocument> pages, String tableText, int semester,
                                                            UUID jobId) {
        AiPurpose purpose = AiPurpose.CURRICULUM;
        return call(purpose, pages, AiPrompts.curriculum(semester, tableText), AiSchemas.curriculum(),
                CurriculumExtraction.class, jobId);
    }

    public AiResult<ProgramHoursExtraction> extractProgramHours(String documentText, UUID jobId) {
        return call(AiPurpose.PROGRAM_HOURS, List.of(), AiPrompts.programHours(documentText), AiSchemas.programHours(),
                ProgramHoursExtraction.class, jobId);
    }

    public AiResult<AbsenceExtraction> parseAbsence(String text, LocalDate today) {
        return call(AiPurpose.ABSENCE_TEXT, List.of(), AiPrompts.absence(text, today), AiSchemas.absence(),
                AbsenceExtraction.class, null);
    }

    private <T> AiResult<T> call(AiPurpose purpose, List<AiDocument> documents, String instruction,
                                 Map<String, Object> schema, Class<T> type, UUID jobId) {
        ensureCanCall();
        boolean vision = purpose.vision() && !documents.isEmpty();
        String model = vision ? properties.modelVision() : properties.modelText();
        String effort = vision ? properties.effortVision() : properties.effortText();
        List<AiDocument> prepared = documents.stream().map(AiDocumentPreparer::prepare).toList();
        AiRequest request = new AiRequest(purpose, model, AiPrompts.SYSTEM, prepared, instruction, schema,
                properties.maxTokens(), effort);
        AiResponse response = port.complete(request);
        String answeredBy = response.model() == null || response.model().isBlank() ? model : response.model();
        switch (response.stop()) {
            case REFUSED -> {
                usage.record(purpose, answeredBy, response.inputTokens(), response.outputTokens(),
                        AiUsageRecord.Outcome.REFUSED, jobId);
                throw new AiExtractionException("Модель відмовилася обробити цей документ. Внесіть дані вручну.");
            }
            case TRUNCATED -> {
                usage.record(purpose, answeredBy, response.inputTokens(), response.outputTokens(),
                        AiUsageRecord.Outcome.TRUNCATED, jobId);
                throw new AiExtractionException("Відповідь моделі обрізано через ліміт токенів — документ завеликий "
                        + "для одного запиту. Розділіть його на частини.");
            }
            case COMPLETE -> {
                // далі розбір JSON
            }
        }
        T value;
        try {
            value = jsonMapper.readValue(response.text(), type);
        } catch (JacksonException e) {
            usage.record(purpose, answeredBy, response.inputTokens(), response.outputTokens(),
                    AiUsageRecord.Outcome.INVALID, jobId);
            log.warn("AI {} answer is not valid {}: {}", purpose, type.getSimpleName(), e.getOriginalMessage());
            throw new AiExtractionException("Модель повернула відповідь у неочікуваному форматі.", e);
        }
        AiCallInfo call = usage.record(purpose, answeredBy, response.inputTokens(), response.outputTokens(),
                AiUsageRecord.Outcome.SUCCESS, jobId);
        log.info("AI {} via {}: {} in / {} out tokens, ${}", purpose, answeredBy, call.inputTokens(), call.outputTokens(),
                call.costUsd());
        return new AiResult<>(value, call);
    }
}
