package ua.vtc.rozklad.importer.normalize;

import java.util.List;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * An entity found in a draft that must be matched to the catalog.
 *
 * @param raw      the label the draft uses for it (also the key admins map by)
 * @param variants other spellings of the same entity in the file
 * @param fullName full name when known (disciplines), used for matching by name
 */
public record EntityCandidate(EntityType type, String raw, List<String> variants, String fullName, String location) {

    public EntityCandidate {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    public static EntityCandidate of(EntityType type, String raw, String location) {
        return new EntityCandidate(type, raw, List.of(), null, location);
    }

    /** Key for mappings: type plus normalized raw. */
    public String key() {
        return key(type, raw);
    }

    public static String key(EntityType type, String raw) {
        return type + ":" + TextNormalizer.key(raw);
    }
}
