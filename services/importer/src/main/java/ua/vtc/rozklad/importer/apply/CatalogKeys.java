package ua.vtc.rozklad.importer.apply;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.normalize.Resolution;

/**
 * Natural keys for core requests: an entity that exists in the catalog is referenced by the
 * catalog's key (short name, code, room name); a new one by the draft's key or the admin's
 * {@code createNew} override.
 */
public final class CatalogKeys {

    private final Map<String, Resolution> resolutions;
    private final CatalogSnapshot catalog;

    public CatalogKeys(NormalizationResult normalization, CatalogSnapshot catalog) {
        this.resolutions = normalization.byKey();
        this.catalog = catalog;
    }

    public Optional<Resolution> resolution(EntityType type, String raw) {
        return raw == null ? Optional.empty() : Optional.ofNullable(resolutions.get(EntityCandidate.key(type, raw)));
    }

    /** Key to send to core for {@code raw}; {@code overrideField} is read from {@code createNew}. */
    public String key(EntityType type, String raw, String overrideField) {
        if (raw == null) {
            return null;
        }
        Resolution resolution = resolution(type, raw).orElse(null);
        if (resolution == null) {
            return raw;
        }
        if (resolution.existing()) {
            return switch (type) {
                case TEACHER -> catalog.teacher(resolution.matched().id()).map(CatalogSnapshot.TeacherItem::shortName).orElse(raw);
                case DISCIPLINE -> catalog.discipline(resolution.matched().id())
                        .map(CatalogSnapshot.DisciplineItem::shortName).orElse(raw);
                case GROUP -> catalog.group(resolution.matched().id()).map(CatalogSnapshot.GroupItem::code).orElse(raw);
                case ROOM -> catalog.room(resolution.matched().id()).map(CatalogSnapshot.RoomItem::name).orElse(raw);
            };
        }
        return override(resolution, overrideField).orElse(raw);
    }

    /** Whether the entity is new (not in the catalog) and must be sent in the catalog request. */
    public boolean isNew(EntityType type, String raw) {
        return resolution(type, raw).map(Resolution::createsNew).orElse(true);
    }

    /** A text field of the admin's {@code createNew} object, if given. */
    public static Optional<String> override(Resolution resolution, String field) {
        if (resolution.decision() == null || resolution.decision().createNew() == null) {
            return Optional.empty();
        }
        JsonNode value = resolution.decision().createNew().get(field);
        return value != null && value.isString() && !value.asString().isBlank()
                ? Optional.of(value.asString().strip())
                : Optional.empty();
    }
}
