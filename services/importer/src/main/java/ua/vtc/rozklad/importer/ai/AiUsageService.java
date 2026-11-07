package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.importer.ai.AiUsageRepository.DayTotals;
import ua.vtc.rozklad.importer.config.AiProperties;
import ua.vtc.rozklad.importer.config.Ids;

/**
 * Per-call usage accounting and the daily budget ({@code AI_DAILY_BUDGET_USD}, Kyiv calendar day).
 * The budget is checked before each call; a call already running may finish over it, so the
 * overshoot is bounded by the calls in flight. A budget of 0 disables AI calls.
 */
@Service
public class AiUsageService {

    private final AiUsageRepository usages;
    private final AiPriceTable prices;
    private final AiProperties properties;
    private final Clock clock;

    public AiUsageService(AiUsageRepository usages, AiPriceTable prices, AiProperties properties, Clock clock) {
        this.usages = usages;
        this.prices = prices;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DayTotals today() {
        return usages.totalsOf(LocalDate.now(clock));
    }

    public BigDecimal dailyBudgetUsd() {
        return properties.dailyBudgetUsd();
    }

    /** @throws AiBudgetExceededException when today's spend reached the budget */
    @Transactional(readOnly = true)
    public void ensureWithinBudget() {
        BigDecimal spent = today().costUsd();
        if (spent.compareTo(properties.dailyBudgetUsd()) >= 0) {
            throw new AiBudgetExceededException(spent, properties.dailyBudgetUsd());
        }
    }

    /** Stores one call (even a failed one: its tokens were billed) and returns its cost estimate. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiCallInfo record(AiPurpose purpose, String model, long inputTokens, long outputTokens,
                             AiUsageRecord.Outcome outcome, UUID jobId) {
        BigDecimal cost = prices.cost(model, inputTokens, outputTokens);
        Instant now = clock.instant();
        usages.save(new AiUsageRecord(Ids.newId(), now, LocalDate.now(clock), purpose, model, inputTokens, outputTokens,
                cost, outcome, jobId));
        return new AiCallInfo(model, inputTokens, outputTokens, cost);
    }

    /** Sum of all calls made for a job, or {@code null} when there were none. */
    @Transactional(readOnly = true)
    public AiJobUsage usageOfJob(UUID jobId) {
        List<AiUsageRecord> records = usages.findByJobIdOrderByOccurredAt(jobId);
        AiJobUsage total = null;
        for (AiUsageRecord record : records) {
            AiCallInfo call = new AiCallInfo(record.model(), record.inputTokens(), record.outputTokens(), record.costUsd());
            total = total == null ? AiJobUsage.of(call) : total.plus(call);
        }
        return total;
    }
}
