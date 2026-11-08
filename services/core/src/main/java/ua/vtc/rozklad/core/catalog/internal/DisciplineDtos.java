package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.Discipline;

/** DTOs of disciplines. */
public final class DisciplineDtos {

    private DisciplineDtos() {
    }

    public record DisciplineDto(UUID id, String name, String shortName, String color, boolean active,
                                boolean needsReview, long version) {

        public static DisciplineDto of(Discipline discipline) {
            return new DisciplineDto(discipline.getId(), discipline.getName(), discipline.getShortName(),
                    discipline.getColor(), discipline.isActive(), discipline.isNeedsReview(), discipline.getVersion());
        }
    }

    /**
     * @param color  {@code #RRGGBB} or empty (the UI derives a colour from the id)
     * @param active {@code true} when empty
     */
    public record DisciplineRequest(
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 64) String shortName,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Колір має вигляд #RRGGBB, наприклад #1F6FEB") String color,
            Boolean active,
            Boolean needsReview,
            Long version) {
    }
}
