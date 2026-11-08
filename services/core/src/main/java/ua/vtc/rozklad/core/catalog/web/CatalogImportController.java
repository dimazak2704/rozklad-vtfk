package ua.vtc.rozklad.core.catalog.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.CatalogImportRequest;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportDtos.CatalogImportResult;
import ua.vtc.rozklad.core.catalog.internal.CatalogImportService;

/** {@code POST /api/admin/import/catalog} (ADMIN or SERVICE: the importer applies reviewed drafts). */
@RestController
class CatalogImportController {

    private final CatalogImportService imports;

    CatalogImportController(CatalogImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/admin/import/catalog")
    CatalogImportResult importCatalog(@Valid @RequestBody CatalogImportRequest request) {
        return imports.apply(request);
    }
}
