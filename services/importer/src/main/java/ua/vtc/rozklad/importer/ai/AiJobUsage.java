package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** AI spend of one import job (CONTRACTS §5 {@code ai:{model,inputTokens,outputTokens,costUsd}}). */
public record AiJobUsage(String model, long inputTokens, long outputTokens, BigDecimal costUsd) {

    public AiJobUsage {
        costUsd = costUsd.setScale(6, RoundingMode.HALF_UP);
    }

    public static AiJobUsage of(AiCallInfo call) {
        return new AiJobUsage(call.model(), call.inputTokens(), call.outputTokens(), call.costUsd());
    }

    /** Adds another call; the model of the latest call is shown. */
    public AiJobUsage plus(AiCallInfo call) {
        return new AiJobUsage(call.model(), inputTokens + call.inputTokens(), outputTokens + call.outputTokens(),
                costUsd.add(call.costUsd()));
    }
}
