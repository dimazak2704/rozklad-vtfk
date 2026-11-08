package ua.vtc.rozklad.core.order.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.core.order.internal.draft.OrderDrafts;
import ua.vtc.rozklad.core.order.internal.numbering.OrderNumberingService;
import ua.vtc.rozklad.core.order.internal.numbering.OrderNumberingService.OrderNumberingDto;
import ua.vtc.rozklad.core.order.internal.publish.OrderPublication;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** Orders (розпорядження) for the dispatcher (CONTRACTS §2 «Orders»). */
@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController {

    private final OrderQueries queries;
    private final OrderDrafts drafts;
    private final OrderPublication publication;
    private final OrderNumberingService numbering;

    AdminOrderController(OrderQueries queries, OrderDrafts drafts, OrderPublication publication,
                         OrderNumberingService numbering) {
        this.queries = queries;
        this.drafts = drafts;
        this.publication = publication;
        this.numbering = numbering;
    }

    @GetMapping
    PageResponse<OrderDto.Summary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.list(from, to, q, status, page, size);
    }

    /** Builds or refreshes the draft of the date (an amendment once the order of the date is published). */
    @GetMapping("/draft")
    OrderDto draft(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return drafts.draftFor(date);
    }

    /** First, last and next number of the semester's orders. */
    @GetMapping("/numbering")
    OrderNumberingDto numbering(@RequestParam UUID semesterId) {
        return numbering.get(semesterId);
    }

    /** The system continues paper numbering (e.g. from №35); 422 when the number is already passed. */
    @PutMapping("/numbering")
    OrderNumberingDto setNumbering(@Valid @RequestBody NumberingRequest request, CurrentUser user) {
        return numbering.setFirstNumber(request.semesterId(), request.firstNumber(), user.username());
    }

    @GetMapping("/{id}")
    OrderDto get(@PathVariable UUID id) {
        return queries.get(id);
    }

    /** Discards a draft (204); a published order is 422 {@code ORDER_ALREADY_PUBLISHED}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable UUID id) {
        drafts.discard(id);
    }

    @PutMapping("/{id}/notes")
    OrderDto notes(@PathVariable UUID id, @Valid @RequestBody NotesRequest request) {
        return drafts.editNotes(id, request.notes());
    }

    /** Number assigned, changes applied, {@code order.published} sent; 409 for conflicts or a stale draft. */
    @PostMapping("/{id}/publish")
    OrderDto publish(@PathVariable UUID id, CurrentUser user) {
        return publication.publish(id, user.username());
    }

    record NumberingRequest(@NotNull UUID semesterId,
                            @NotNull @Min(value = 1, message = "Номер — від 1 до 9999")
                            @Max(value = 9999, message = "Номер — від 1 до 9999") Integer firstNumber) {
    }

    record NotesRequest(@NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> notes) {
    }
}
