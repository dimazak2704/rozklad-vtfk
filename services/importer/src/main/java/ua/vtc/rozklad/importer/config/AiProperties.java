package ua.vtc.rozklad.importer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI extraction settings ({@code app.ai.*}). An empty {@code apiKey} disables the Anthropic
 * adapter (deterministic parsers still work). The key is never logged.
 *
 * @param provider     {@code anthropic} (real calls) or {@code fake} (scripted answers for tests)
 * @param baseUrl      optional API base URL override (tests point it to a local stub)
 * @param effortText   output effort for text tasks (absence text, program hours)
 * @param effortVision output effort for photos and scans (orders, curriculum)
 * @param maxTokens    output token cap per call (thinking included)
 * @param prices       USD per million tokens by model id; {@code defaultPrice} for unknown models
 */
@Validated
@ConfigurationProperties("app.ai")
public record AiProperties(
        String apiKey,
        @NotBlank String provider,
        String baseUrl,
        @NotBlank String modelText,
        @NotBlank String modelVision,
        @NotNull @DecimalMin("0.00") BigDecimal dailyBudgetUsd,
        @NotNull Duration timeout,
        @Min(0) int maxRetries,
        @NotBlank String effortText,
        @NotBlank String effortVision,
        @Min(1024) long maxTokens,
        Map<String, @Valid ModelPrice> prices,
        @NotNull @Valid ModelPrice defaultPrice) {

    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_FAKE = "fake";

    public AiProperties {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    /**
     * Prices in USD per million tokens. Optional long-context tier: prompts longer than
     * {@code longContextThreshold} input tokens use the {@code long*} prices (Claude Haiku 5.5).
     */
    public record ModelPrice(@NotNull @DecimalMin("0.00") BigDecimal inputPerMtok,
                             @NotNull @DecimalMin("0.00") BigDecimal outputPerMtok,
                             Long longContextThreshold, BigDecimal longInputPerMtok, BigDecimal longOutputPerMtok) {
    }

    public boolean enabled() {
        return PROVIDER_FAKE.equals(provider) || apiKey != null && !apiKey.isBlank();
    }

    public ModelPrice priceOf(String model) {
        return prices.getOrDefault(model, defaultPrice);
    }

    @Override
    public String toString() {
        return "AiProperties[apiKey=" + (apiKey != null && !apiKey.isBlank() ? "***" : "<empty>") + ", provider=" + provider
                + ", modelText=" + modelText + ", modelVision=" + modelVision + ", dailyBudgetUsd=" + dailyBudgetUsd + "]";
    }
}
