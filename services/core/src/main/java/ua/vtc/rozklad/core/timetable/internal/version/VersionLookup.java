package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionStatus;

/** Finds versions and enforces "only a draft can be edited". */
@Component
public class VersionLookup {

    private final TimetableVersionRepository versions;

    public VersionLookup(TimetableVersionRepository versions) {
        this.versions = versions;
    }

    public TimetableVersion require(UUID versionId) {
        return versions.findById(versionId).orElseThrow(() -> NotFoundException.of("Версію розкладу", versionId));
    }

    /** Locks the draft row for the rest of the transaction (edits and publication are serialized). */
    public TimetableVersion requireDraftForUpdate(UUID versionId) {
        TimetableVersion version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> NotFoundException.of("Версію розкладу", versionId));
        requireDraft(version);
        return version;
    }

    public TimetableVersion requireDraft(UUID versionId) {
        TimetableVersion version = require(versionId);
        requireDraft(version);
        return version;
    }

    public static void requireDraft(TimetableVersion version) {
        if (!version.isDraft()) {
            throw new ConflictException("Змінювати можна лише чернетку: версія «%s» %s."
                    .formatted(version.getName(), statusLabel(version.getStatus())));
        }
    }

    static String statusLabel(VersionStatus status) {
        return switch (status) {
            case DRAFT -> "є чернеткою";
            case PUBLISHED -> "уже опублікована";
            case ARCHIVED -> "в архіві";
        };
    }
}
