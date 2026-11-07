package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;

/** Today's AI spend reached {@code AI_DAILY_BUDGET_USD}; answered with 429. */
public class AiBudgetExceededException extends RuntimeException {

    private final BigDecimal spentUsd;
    private final BigDecimal budgetUsd;

    public AiBudgetExceededException(BigDecimal spentUsd, BigDecimal budgetUsd) {
        super("Денний бюджет на розпізнавання ШІ вичерпано: витрачено $" + spentUsd.stripTrailingZeros().toPlainString()
                + " з $" + budgetUsd.stripTrailingZeros().toPlainString() + ". Спробуйте завтра або збільште бюджет.");
        this.spentUsd = spentUsd;
        this.budgetUsd = budgetUsd;
    }

    public BigDecimal spentUsd() {
        return spentUsd;
    }

    public BigDecimal budgetUsd() {
        return budgetUsd;
    }
}
