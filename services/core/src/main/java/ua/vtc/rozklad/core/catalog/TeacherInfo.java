package ua.vtc.rozklad.core.catalog;

import java.util.UUID;

/**
 * Read model of a teacher for other modules. {@code keycloakUserId} links the account created by
 * an invite ({@code null} = no account yet).
 */
public record TeacherInfo(
        UUID id,
        String lastName,
        String firstName,
        String middleName,
        String shortName,
        String displayName,
        UUID cycleCommissionId,
        Employment employment,
        boolean active,
        UUID keycloakUserId) {

    public static TeacherInfo of(Teacher teacher) {
        return new TeacherInfo(teacher.getId(), teacher.getLastName(), teacher.getFirstName(),
                teacher.getMiddleName(), teacher.getShortName(), teacher.getDisplayName(),
                teacher.getCycleCommissionId(), teacher.getEmployment(), teacher.isActive(),
                teacher.getKeycloakUserId());
    }

    public TeacherRef ref() {
        return new TeacherRef(id, shortName, displayName);
    }

    /** «Кренцін Микола Дмитрович» when the first name is known, otherwise the short name. */
    public String fullName() {
        return TeacherNames.fullName(lastName, firstName, middleName, shortName);
    }
}
