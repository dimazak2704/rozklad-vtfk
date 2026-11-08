package ua.vtc.rozklad.core.order.internal.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.order.internal.importing.HistoricOrderImport;
import ua.vtc.rozklad.core.order.internal.importing.HistoricOrderRequest;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/**
 * {@code POST /api/admin/import/changes} (ADMIN or the importer's SERVICE account): an order issued
 * on paper becomes a published order of record (text only, nothing is applied or distributed).
 * A repeated import of the same order answers with the stored one ({@code created: false}).
 */
@RestController
class OrderImportController {

    private final HistoricOrderImport imports;

    OrderImportController(HistoricOrderImport imports) {
        this.imports = imports;
    }

    @PostMapping("/api/admin/import/changes")
    HistoricOrderRequest.Result importOrder(@Valid @RequestBody HistoricOrderRequest request, CurrentUser user) {
        return imports.importOrder(request, user.username());
    }
}
