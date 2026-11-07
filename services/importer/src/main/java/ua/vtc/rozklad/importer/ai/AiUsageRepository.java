package ua.vtc.rozklad.importer.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageRepository extends JpaRepository<AiUsageRecord, UUID> {

    /** Totals of one day: calls, input and output tokens, cost. */
    record DayTotals(long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {
    }

    @Query("""
            select new ua.vtc.rozklad.importer.ai.AiUsageRepository$DayTotals(
                count(u), coalesce(sum(u.inputTokens), 0), coalesce(sum(u.outputTokens), 0), coalesce(sum(u.costUsd), 0))
            from AiUsageRecord u where u.usageDate = :day
            """)
    DayTotals totalsOf(@Param("day") LocalDate day);

    List<AiUsageRecord> findByJobIdOrderByOccurredAt(UUID jobId);
}
