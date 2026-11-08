package ua.vtc.rozklad.importer.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.importer.job.ImportJobDto;
import ua.vtc.rozklad.importer.job.ImportJobService;
import ua.vtc.rozklad.importer.job.ImportKind;

/**
 * {@code PUT /api/import/jobs/{id}/draft} (ADMIN): the review step of an order import. The admin
 * corrects the recognized rows against the photo and saves them; only a reviewed AI draft can be
 * applied (importer.md §3, ORDER_DOCUMENT).
 */
@RestController
@Tag(name = "Імпорт")
public class OrderDraftController {

    private final ImportJobService jobs;
    private final OrderImportHandler handler;

    public OrderDraftController(ImportJobService jobs, OrderImportHandler handler) {
        this.jobs = jobs;
        this.handler = handler;
    }

    @Operation(summary = "Зберегти звірені (виправлені) рядки розпорядження перед застосуванням")
    @PutMapping("/api/import/jobs/{id}/draft")
    public ImportJobDto revise(@PathVariable UUID id, @Valid @RequestBody OrderDraftEdit edit) {
        return ImportJobDto.of(jobs.reviseDraft(id, ImportKind.ORDER_DOCUMENT, edit.version(),
                draft -> handler.revise(draft, edit)));
    }
}
