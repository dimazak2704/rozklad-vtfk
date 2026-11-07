package ua.vtc.rozklad.notification.delivery.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundKind;
import ua.vtc.rozklad.notification.delivery.OutboundMessage;
import ua.vtc.rozklad.notification.delivery.OutboundMessageRepository;
import ua.vtc.rozklad.notification.delivery.OutboundStatus;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.shared.PageResponse;

/** Delivery log: every push message with its status, attempts and last error. */
@RestController
@RequestMapping("/api/notify/deliveries")
@Validated
@Tag(name = "Журнал доставки")
public class DeliveryController {

    private final OutboundMessageRepository repository;

    public DeliveryController(OutboundMessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Журнал доставки (нові спершу), фільтри orderId і status")
    public PageResponse<DeliveryDto> list(@RequestParam(required = false) UUID orderId,
                                          @RequestParam(required = false) OutboundStatus status,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return PageResponse.of(repository.search(orderId, status, PageRequest.of(page, size)), DeliveryDto::of);
    }

    /** {@code chatId} is masked; {@code error} is the last failure reason. */
    public record DeliveryDto(UUID id, UUID orderId, String chatId, String chatTitle, BindingType bindingType,
                              DeliveryPurpose purpose, OutboundKind kind, OutboundStatus status, int attempts,
                              String error, Instant createdAt, Instant sentAt, Instant nextAttemptAt) {

        public static DeliveryDto of(OutboundMessage message) {
            return new DeliveryDto(message.getId(), message.getOrderId(), ChatIds.mask(message.getChatId()),
                    message.getChatTitle(), message.getBindingType(), message.getPurpose(), message.getKind(),
                    message.getStatus(), message.getAttempts(), message.getLastError(), message.getCreatedAt(),
                    message.getSentAt(),
                    message.getStatus().isPending() ? message.getNextAttemptAt() : null);
        }
    }
}
