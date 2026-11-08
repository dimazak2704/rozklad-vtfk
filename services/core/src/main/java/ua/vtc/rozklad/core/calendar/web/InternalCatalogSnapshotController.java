package ua.vtc.rozklad.core.calendar.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.calendar.internal.CatalogSnapshotService;
import ua.vtc.rozklad.core.calendar.internal.CatalogSnapshotService.CatalogSnapshot;

/** {@code GET /api/internal/catalog/snapshot} (SERVICE): input of the importer's normalization. */
@RestController
class InternalCatalogSnapshotController {

    private final CatalogSnapshotService snapshots;

    InternalCatalogSnapshotController(CatalogSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/api/internal/catalog/snapshot")
    CatalogSnapshot snapshot() {
        return snapshots.snapshot();
    }
}
