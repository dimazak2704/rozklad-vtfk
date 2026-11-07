package ua.vtc.rozklad.importer.normalize;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Admin decision for one raw value (CONTRACTS §5 {@code mappings[]}): map to an existing catalog
 * entity ({@code targetId}), or create a new one ({@code createNew}, optional field overrides);
 * {@code addAlias} stores the raw spelling as an alias of the target when applying.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingDecision(EntityType type, String raw, UUID targetId, JsonNode createNew, boolean addAlias) {

    public String key() {
        return EntityCandidate.key(type, raw);
    }

    public boolean mapsToExisting() {
        return targetId != null;
    }
}
