package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.importer.config.AiProperties;
import ua.vtc.rozklad.importer.config.AiProperties.ModelPrice;

/**
 * Cost estimate of a call from the configurable price table ({@code app.ai.prices}, USD per million
 * tokens). Unknown models use {@code app.ai.default-price} so the daily budget still works.
 */
@Component
public class AiPriceTable {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AiProperties properties;

    public AiPriceTable(AiProperties properties) {
        this.properties = properties;
    }

    public BigDecimal cost(String model, long inputTokens, long outputTokens) {
        ModelPrice price = properties.priceOf(model);
        BigDecimal input = price.inputPerMtok();
        BigDecimal output = price.outputPerMtok();
        if (price.longContextThreshold() != null && inputTokens > price.longContextThreshold()
                && price.longInputPerMtok() != null && price.longOutputPerMtok() != null) {
            input = price.longInputPerMtok();
            output = price.longOutputPerMtok();
        }
        return input.multiply(BigDecimal.valueOf(inputTokens))
                .add(output.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
