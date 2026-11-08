package ua.vtc.rozklad.core.settings;

import java.util.Optional;
import java.util.UUID;

/**
 * Read and write API of system settings for other modules. Every accessor reads the current
 * values from the database; call {@link #current()} once when several values are needed.
 */
public interface SettingsService {

    SystemSettings current();

    /**
     * Validates and stores all values.
     *
     * @param actor username written to {@code setting.updated_by}
     * @throws ua.vtc.rozklad.core.shared.error.FieldValidationException when a value breaks a rule
     */
    SystemSettings update(SystemSettings settings, String actor);

    /**
     * Revision of the stored settings: every update increments it. The settings form sends back the
     * revision it has shown, so two administrators cannot silently overwrite each other.
     */
    long version();

    /**
     * {@link #update(SystemSettings, String)} only while the settings are still at {@code expectedVersion}.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException when someone saved them meanwhile
     */
    SystemSettings update(SystemSettings settings, long expectedVersion, String actor);

    default ChangeApprovalMode approvalMode() {
        return current().approvalMode();
    }

    default boolean requiresConfirmation() {
        return current().requiresConfirmation();
    }

    default int confirmTimeoutMinutes() {
        return current().confirmTimeoutMinutes();
    }

    default String signatoryPosition() {
        return current().signatoryPosition();
    }

    default String signatoryName() {
        return current().signatoryName();
    }

    default String collegeName() {
        return current().collegeName();
    }

    default int hoursPerPair() {
        return current().hoursPerPair();
    }

    default boolean showTeacherFullNames() {
        return current().showTeacherFullNames();
    }

    default Optional<UUID> activeBellProfileId() {
        return Optional.ofNullable(current().activeBellProfileId());
    }
}
