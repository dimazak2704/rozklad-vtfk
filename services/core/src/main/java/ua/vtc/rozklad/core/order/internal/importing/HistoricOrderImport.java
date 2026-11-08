package ua.vtc.rozklad.core.order.internal.importing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.OrderItemContent;
import ua.vtc.rozklad.core.order.OrderSection;
import ua.vtc.rozklad.core.order.internal.draft.OrderTexts;
import ua.vtc.rozklad.core.order.internal.support.OrderCalendar;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/**
 * Stores an order that was issued on paper before the system (or beside it) as a published order
 * of record: its number, date, rows, notes and signature exactly as printed. It is history only:
 * no change is created, no lesson is touched and nothing is distributed (no {@code order.published}),
 * because the rows are text the dispatcher has not matched to lessons. Its number counts in the
 * semester's numbering, so the next published order continues after it. Importing the same order
 * again (same number, date and rows) answers with the stored one; the same number with other rows
 * is a 409 — unless the request says {@code replace} and the stored order was imported too: then the
 * corrected rows, notes, labels and signature replace the earlier import (a misreading fixed by the
 * admin). An order the system published itself is never replaced.
 */
@Service
@Transactional
public class HistoricOrderImport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrderDocumentRepository orders;
    private final OrderCalendar calendar;
    private final Clock clock;

    public HistoricOrderImport(OrderDocumentRepository orders, OrderCalendar calendar, Clock clock) {
        this.orders = orders;
        this.calendar = calendar;
        this.clock = clock;
    }

    public HistoricOrderRequest.Result importOrder(HistoricOrderRequest request, String actor) {
        LocalDate date = request.date();
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("ORDER_IMPORT_FUTURE_DATE", ("Розпорядження на %s ще не настало: імпортують "
                    + "лише видані розпорядження, а майбутні зміни створюють у системі.").formatted(date.format(DATE)));
        }
        SemesterInfo semester = calendar.requireOrderDate(date);
        OrderKind kind = request.kind() == null ? OrderKind.ORDER : request.kind();
        // той самий замок, що й у публікації: номер і «одне розпорядження на дату» перевіряються без гонок
        orders.lockNumbering(semester.id());
        orders.lockDate(semester.id(), date);

        Optional<OrderDocument> sameNumber = orders.findBySemesterIdAndNumber(semester.id(), request.number());
        if (sameNumber.isPresent()) {
            OrderDocument existing = sameNumber.get();
            if (existing.getDate().equals(date) && existing.getKind() == kind) {
                // повтор того самого імпорту нічого не змінює; інший текст під тим самим номером — не повтор
                if (contents(existing).equals(contents(request)) && !Boolean.TRUE.equals(request.replace())) {
                    return result(existing, false, false);
                }
                if (Boolean.TRUE.equals(request.replace()) && existing.isImported()) {
                    existing.replaceImportedItems();
                    orders.flush();
                    fill(existing, request);
                    orders.saveAndFlush(existing);
                    return result(existing, false, true);
                }
                throw new ConflictException(("На %s уже є розпорядження №%d, і його рядки відрізняються від "
                        + "імпортованих. Звірте розпорядження в системі з паперовим.").formatted(date.format(DATE),
                        request.number()));
            }
            throw new ConflictException("Розпорядження №%d уже є в семестрі «%s» (на %s).".formatted(request.number(),
                    semester.name(), existing.getDate().format(DATE)));
        }
        OrderDocument order = kind == OrderKind.ORDER ? mainOrder(semester, request) : amendment(semester, request);
        order.markImported();
        fill(order, request);
        order.markPublished(request.number(), clock.instant(), actor);
        orders.saveAndFlush(order);
        return result(order, true, false);
    }

    /** Printed labels, signature, notes and rows of the request. */
    private void fill(OrderDocument order, HistoricOrderRequest request) {
        order.setDayLabels(label(request.weekdayLabel(), OrderTexts.weekdayLabel(request.date())),
                label(request.parityLabel(), parityLabel(request.date())));
        order.setSignatory(blankToNull(request.signatoryPosition()), blankToNull(request.signatoryName()));
        order.setNotes(request.notes() == null ? List.of() : request.notes().stream().map(String::strip).toList());
        for (HistoricOrderRequest.Item item : request.items()) {
            order.addItem(item.section() == null ? OrderSection.CHANGES : item.section(), content(item));
        }
    }

    /** Rows as stored: the main table first, then room changes, each in printed order. */
    private static List<OrderItemContent> contents(OrderDocument order) {
        return order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getSection).thenComparingInt(OrderItem::getPosition))
                .map(OrderItem::content)
                .toList();
    }

    private static List<OrderItemContent> contents(HistoricOrderRequest request) {
        return request.items().stream()
                .sorted(Comparator.comparing(item -> item.section() == null ? OrderSection.CHANGES : item.section()))
                .map(HistoricOrderImport::content)
                .toList();
    }

    private static OrderItemContent content(HistoricOrderRequest.Item item) {
        return new OrderItemContent(item.groupCode().strip(), item.pairLabel().strip(), item.rowType(),
                blankToNull(item.fromDiscipline()), blankToNull(item.fromTeacher()), blankToNull(item.reasonText()),
                blankToNull(item.toDiscipline()), blankToNull(item.toTeacher()), blankToNull(item.roomText()), null);
    }

    private OrderDocument mainOrder(SemesterInfo semester, HistoricOrderRequest request) {
        orders.findBySemesterIdAndDateAndKind(semester.id(), request.date(), OrderKind.ORDER).ifPresent(existing -> {
            throw new ConflictException(existing.getStatus() == OrderStatus.PUBLISHED
                    ? "На %s уже є розпорядження №%d.".formatted(request.date().format(DATE), existing.getNumber())
                    : "На %s уже є чернетка розпорядження: опублікуйте її або видаліть, щоб імпортувати паперове."
                    .formatted(request.date().format(DATE)));
        });
        return OrderDocument.mainOrder(semester.id(), request.date(), OrderTexts.mainTitle(request.number()));
    }

    private OrderDocument amendment(SemesterInfo semester, HistoricOrderRequest request) {
        if (request.parentNumber() == null) {
            throw FieldValidationException.of("parentNumber", "Вкажіть номер розпорядження, яке доповнюють.");
        }
        OrderDocument parent = orders.findBySemesterIdAndNumber(semester.id(), request.parentNumber())
                .filter(found -> found.getKind() == OrderKind.ORDER && found.getDate().equals(request.date()))
                .orElseThrow(() -> new BusinessRuleException("ORDER_PARENT_NOT_FOUND",
                        "Розпорядження №%d на %s не знайдено: спершу імпортуйте його.".formatted(request.parentNumber(),
                                request.date().format(DATE))));
        return OrderDocument.amendment(semester.id(), request.date(), parent.getId(),
                OrderTexts.amendmentTitle(request.parentNumber()));
    }

    private String parityLabel(LocalDate date) {
        Parity parity = calendar.parityOrNull(date);
        return parity == null ? null : OrderTexts.parityLabel(parity);
    }

    private static String label(String printed, String computed) {
        String clean = blankToNull(printed);
        return clean != null ? clean : computed;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static HistoricOrderRequest.Result result(OrderDocument order, boolean created, boolean replaced) {
        return new HistoricOrderRequest.Result(order.getId(), Objects.requireNonNull(order.getNumber()), order.getDate(),
                order.getKind(), order.getItems().size(), created, replaced);
    }
}
