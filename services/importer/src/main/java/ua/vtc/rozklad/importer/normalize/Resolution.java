package ua.vtc.rozklad.importer.normalize;

import java.util.List;
import ua.vtc.rozklad.importer.report.ScoredRef;

/** How one draft entity relates to the catalog after normalization and admin mappings. */
public record Resolution(EntityCandidate entity, Outcome outcome, ScoredRef matched, List<ScoredRef> suggestions,
                         MappingDecision decision) {

    public enum Outcome {
        /** Found automatically (alias, exact name). */
        MATCHED,
        /** Mapped by the admin to an existing entity. */
        MAPPED,
        /** Not in the catalog: a new entity will be created. */
        NEW,
        /** Similar entities exist; the admin must decide (map or create). */
        AMBIGUOUS,
        /** The admin's mapping points to an entity that is no longer in the catalog. */
        INVALID_MAPPING
    }

    public Resolution {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** The entity exists in the catalog (automatically or by mapping). */
    public boolean existing() {
        return outcome == Outcome.MATCHED || outcome == Outcome.MAPPED;
    }

    /** The entity will be created when applying. */
    public boolean createsNew() {
        return outcome == Outcome.NEW;
    }
}
