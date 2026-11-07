package ua.vtc.rozklad.core.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Schedule conflict or warning ({@code Conflict} in CONTRACTS.md). For dated lessons
 * {@code date} is set, for template lessons {@code weekday}; {@code pair} is the pair number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConflictDto(
        ConflictKind kind,
        Severity severity,
        String message,
        List<UUID> lessonIds,
        LocalDate date,
        Integer weekday,
        int pair) {

    public ConflictDto {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        lessonIds = lessonIds == null ? List.of() : List.copyOf(lessonIds);
    }

    public static ConflictDto hard(ConflictKind kind, String message, List<UUID> lessonIds, LocalDate date, int pair) {
        return new ConflictDto(kind, Severity.HARD, message, lessonIds, date, null, pair);
    }

    public static ConflictDto hardInTemplate(ConflictKind kind, String message, List<UUID> lessonIds, int weekday,
                                             int pair) {
        return new ConflictDto(kind, Severity.HARD, message, lessonIds, null, weekday, pair);
    }

    public boolean isHard() {
        return severity == Severity.HARD;
    }

    public enum Severity {
        HARD,
        SOFT
    }
}
