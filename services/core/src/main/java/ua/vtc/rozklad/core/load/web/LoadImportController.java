package ua.vtc.rozklad.core.load.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportRequest;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportResult;
import ua.vtc.rozklad.core.load.internal.LoadImportService;

/** {@code POST /api/admin/import/load} (ADMIN or SERVICE): upsert of load items by natural keys. */
@RestController
class LoadImportController {

    private final LoadImportService imports;

    LoadImportController(LoadImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/admin/import/load")
    LoadImportResult importLoad(@Valid @RequestBody LoadImportRequest request) {
        return imports.apply(request);
    }
}
