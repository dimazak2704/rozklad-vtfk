package ua.vtc.rozklad.core.catalog;

import java.util.List;

/**
 * Published (in-process, not externalized) after {@code POST /api/admin/import/catalog} commits,
 * so other modules can react to records that did not exist before, e.g. the demo seed links
 * calendar periods to imported groups.
 *
 * @param groupCodes canonical codes of all groups in the import (created or updated)
 */
public record CatalogImported(int created, int updated, List<String> groupCodes) {

    public CatalogImported {
        groupCodes = groupCodes == null ? List.of() : List.copyOf(groupCodes);
    }
}
