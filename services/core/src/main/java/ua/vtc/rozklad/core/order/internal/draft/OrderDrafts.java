package ua.vtc.rozklad.core.order.internal.draft;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.OrderItemContent;
import ua.vtc.rozklad.core.order.OrderSection;
import ua.vtc.rozklad.core.order.internal.support.OrderCalendar;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.order.internal.view.OrderViews;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Drafts of orders (DOMAIN §8). The draft of a date holds the rows of all CONFIRMED (not yet
 * applied) changes of the date, the notes of the calendar periods active on it (until the
 * dispatcher edits them) and the signature from the settings. Once the main order of the date
 * is published, newly confirmed changes go into an amendment («ДОПОВНЕННЯ до розпорядження №N»)
 * with only the new rows. Building a draft of one date is serialized by an advisory lock.
 */
@Service
@Transactional
public class OrderDrafts {

    static final Set<ChangeStatus> CONFIRMED = EnumSet.of(ChangeStatus.CONFIRMED);

    private final OrderDocumentRepository orders;
    private final ChangeRepository changes;
    private final ChangeRows rows;
    private final OrderCalendar calendar;
    private final SettingsService settings;

    public OrderDrafts(OrderDocumentRepository orders, ChangeRepository changes, ChangeRows rows,
                       OrderCalendar calendar, SettingsService settings) {
        this.orders = orders;
        this.changes = changes;
        this.rows = rows;
        this.calendar = calendar;
        this.settings = settings;
    }

    /**
     * {@code GET /api/admin/orders/draft?date=}: the draft of the date, created or refreshed. When
     * the date's order is published and nothing new is confirmed, the latest published document of
     * the date is returned as it is (no empty amendment is created).
     */
    public OrderDto draftFor(LocalDate date) {
        SemesterInfo semester = calendar.requireOrderDate(date);
        orders.lockDate(semester.id(), date);
        List<Change> confirmed = changes.findAllByDateAndStatusIn(date, CONFIRMED);
        Optional<OrderDocument> existing = orders.findBySemesterIdAndDateAndKind(semester.id(), date,
                OrderKind.ORDER);
        if (existing.isEmpty() || existing.get().isDraft()) {
            OrderDocument main = existing.orElseGet(() -> OrderDocument.mainOrder(semester.id(), date,
                    OrderTexts.mainTitle(null)));
            return save(refresh(main, semester, confirmed));
        }
        OrderDocument main = existing.get();
        Optional<OrderDocument> amendmentDraft = orders.findFirstBySemesterIdAndDateAndKindAndStatus(
                semester.id(), date, OrderKind.AMENDMENT, OrderStatus.DRAFT);
        if (amendmentDraft.isEmpty() && confirmed.isEmpty()) {
            return OrderViews.toDto(latestPublished(semester, date).orElse(main));
        }
        OrderDocument amendment = amendmentDraft.orElseGet(() -> OrderDocument.amendment(semester.id(), date,
                main.getId(), OrderTexts.amendmentTitle(main.getNumber())));
        return save(refresh(amendment, semester, confirmed));
    }

    /** {@code PUT /api/admin/orders/{id}/notes}: the dispatcher's notes replace the calendar notes. */
    public OrderDto editNotes(UUID orderId, List<String> notes) {
        OrderDocument order = orders.findWithItemsById(orderId)
                .orElseThrow(() -> NotFoundException.of("Розпорядження", orderId));
        if (!order.isDraft()) {
            throw new BusinessRuleException("ORDER_ALREADY_PUBLISHED", "Опубліковане розпорядження змінювати не можна.");
        }
        order.editNotes(notes.stream().map(String::strip).toList());
        orders.flush();
        return OrderViews.toDto(order);
    }

    /** Rows rebuilt from the confirmed changes (kept as they are when nothing changed). */
    OrderDocument refresh(OrderDocument order, SemesterInfo semester, List<Change> confirmed) {
        OrderRowComposer.Rows composed = OrderRowComposer.compose(rows.of(confirmed));
        if (!sameRows(order, composed)) {
            order.clearItems();
            composed.changes().forEach(content -> order.addItem(OrderSection.CHANGES, content));
            composed.roomChanges().forEach(content -> order.addItem(OrderSection.ROOM_CHANGES, content));
        }
        order.setDayLabels(OrderTexts.weekdayLabel(order.getDate()),
                OrderTexts.parityLabel(calendar.parity(order.getDate())));
        SystemSettings current = settings.current();
        order.setSignatory(current.signatoryPosition(), current.signatoryName());
        if (order.getKind() == OrderKind.ORDER && !order.isNotesEdited()) {
            order.setNotes(calendar.orderNotes(order.getDate()));
        }
        return order;
    }

    /** {@code true} when the order already has exactly these rows (used to detect a stale draft). */
    public static boolean sameRows(OrderDocument order, OrderRowComposer.Rows composed) {
        return contents(order, OrderSection.CHANGES).equals(composed.changes())
                && contents(order, OrderSection.ROOM_CHANGES).equals(composed.roomChanges());
    }

    private static List<OrderItemContent> contents(OrderDocument order, OrderSection section) {
        return order.getItems(section).stream().map(OrderItem::content).toList();
    }

    private OrderDto save(OrderDocument order) {
        orders.save(order);
        orders.flush();
        return OrderViews.toDto(order);
    }

    private Optional<OrderDocument> latestPublished(SemesterInfo semester, LocalDate date) {
        return orders.findAllByDateAndStatusOrderByCreatedAt(date, OrderStatus.PUBLISHED).stream()
                .filter(order -> order.getSemesterId().equals(semester.id()))
                .max(Comparator.comparing(OrderDocument::getNumber));
    }
}
