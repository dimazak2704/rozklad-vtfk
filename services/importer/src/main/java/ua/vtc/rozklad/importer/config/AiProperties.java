package ua.vtc.rozklad.importer.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI extraction settings ({@code app.ai.*}). An empty {@code apiKey} disables the Anthropic
 * adapter (deterministic parsers still work). The key is never logged.
 */
@Validated
@ConfigurationProperties("app.ai")
public record AiProperties(
        String apiKey,
        @NotBlank String modelText,
        @NotBlank String modelVision,
        @NotNull @DecimalMin("0.00") BigDecimal dailyBudgetUsd) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AiProperties[apiKey=" + (enabled() ? "***" : "<empty>") + ", modelText=" + modelText
                + ", modelVision=" + modelVision + ", dailyBudgetUsd=" + dailyBudgetUsd + "]";
    }
}
