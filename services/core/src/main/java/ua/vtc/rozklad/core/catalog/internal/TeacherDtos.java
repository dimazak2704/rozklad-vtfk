package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.Employment;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.web.Ref;

/** DTOs of teachers, their aliases and competences. */
public final class TeacherDtos {

    private TeacherDtos() {
    }

    /** {@code linked} = the teacher has a Keycloak account (registered by invite). */
    public record TeacherDto(
            UUID id,
            String lastName,
            String firstName,
            String middleName,
            String shortName,
            String displayName,
            Ref cycleCommission,
            Employment employment,
            String email,
            boolean active,
            boolean needsReview,
            boolean linked,
            long version) {

        static TeacherDto of(Teacher teacher, Ref cycleCommission) {
            return new TeacherDto(teacher.getId(), teacher.getLastName(), teacher.getFirstName(),
                    teacher.getMiddleName(), teacher.getShortName(), teacher.getDisplayName(), cycleCommission,
                    teacher.getEmployment(), teacher.getEmail(), teacher.isActive(), teacher.isNeedsReview(),
                    teacher.getKeycloakUserId() != null, teacher.getVersion());
        }
    }

    /**
     * @param shortName   «Кренцін М.Д.»; derived from the name parts when empty
     * @param displayName compact public form; the surname when empty
     * @param employment  STAFF when empty
     * @param active      {@code true} when empty
     * @param needsReview {@code false} when empty
     */
    public record TeacherRequest(
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @Size(max = 100) String shortName,
            @Size(max = 100) String displayName,
            UUID cycleCommissionId,
            Employment employment,
            @Email(message = "Некоректна адреса електронної пошти") @Size(max = 254) String email,
            Boolean active,
            Boolean needsReview,
            Long version) {
    }

    public record AliasDto(UUID id, String alias) {
    }

    public record AliasRequest(@NotBlank @Size(max = 100) String alias) {
    }

    public record CompetencesRequest(@NotNull List<@NotNull UUID> disciplineIds) {
    }

    public record CompetenceRequest(@NotNull UUID disciplineId) {
    }
}
