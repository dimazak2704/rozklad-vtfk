package ua.vtc.rozklad.importer.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.importer.ai.AiUsageRepository.DayTotals;
import ua.vtc.rozklad.importer.config.AiProperties;

/** CONTRACTS §5 {@code GET /api/import/ai/usage} (role ADMIN). */
@RestController
@Tag(name = "ШІ", description = "Облік витрат на розпізнавання")
public class AiUsageController {

    /** {@code enabled} and {@code remainingUsd} are additive to the contract. */
    public record AiUsageDto(Today today, BigDecimal dailyBudgetUsd, BigDecimal remainingUsd, Models model, boolean enabled) {
    }

    public record Today(long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {
    }

    public record Models(String text, String vision) {
    }

    private final AiUsageService usage;
    private final AiExtractionService ai;
    private final AiProperties properties;

    public AiUsageController(AiUsageService usage, AiExtractionService ai, AiProperties properties) {
        this.usage = usage;
        this.ai = ai;
        this.properties = properties;
    }

    @Operation(summary = "Витрати на ШІ за сьогодні (за київським часом) і денний бюджет")
    @GetMapping("/api/import/ai/usage")
    public AiUsageDto usage() {
        DayTotals today = usage.today();
        BigDecimal budget = usage.dailyBudgetUsd();
        BigDecimal remaining = budget.subtract(today.costUsd()).max(BigDecimal.ZERO);
        return new AiUsageDto(new Today(today.calls(), today.inputTokens(), today.outputTokens(), today.costUsd()), budget,
                remaining, new Models(properties.modelText(), properties.modelVision()), ai.available());
    }
}
