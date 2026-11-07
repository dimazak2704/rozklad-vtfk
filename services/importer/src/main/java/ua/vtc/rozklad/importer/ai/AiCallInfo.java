package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;

/** Usage of one model call: model id, tokens and the cost estimate from the price table. */
public record AiCallInfo(String model, long inputTokens, long outputTokens, BigDecimal costUsd) {
}
