package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.internal.draft.OrderTexts;
import ua.vtc.rozklad.core.order.internal.support.OrderCalendar;
import ua.vtc.rozklad.core.shared.ref.GroupRef;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Public changes of a date, taken from the published orders only (the document of record): the
 * main order first, then its amendments, rows grouped by group and ordered by course and code.
 */
@Service
@Transactional(readOnly = true)
class PublicChanges {

    private final OrderDocumentRepository orders;
    private final ChangeRepository changes;
    private final StudentGroupRepository groups;
    private final OrderCalendar calendar;

    PublicChanges(OrderDocumentRepository orders, ChangeRepository changes, StudentGroupRepository groups,
                  OrderCalendar calendar) {
        this.orders = orders;
        this.changes = changes;
        this.groups = groups;
        this.calendar = calendar;
    }

    PublicChangesDto of(LocalDate date) {
        Parity parity = calendar.findSemester(date).map(semester -> calendar.parity(semester, date)).orElse(null);
        List<OrderDocument> published = orders.findAllByDateAndStatusOrderByCreatedAt(date, OrderStatus.PUBLISHED)
                .stream()
                .sorted(Comparator.comparing((OrderDocument order) -> order.getKind() == OrderKind.AMENDMENT)
                        .thenComparing(OrderDocument::getNumber))
                .toList();
        PublicChangesDto.PublishedOrder main = published.stream()
                .filter(order -> order.getKind() == OrderKind.ORDER)
                .findFirst()
                .map(order -> new PublicChangesDto.PublishedOrder(order.getId(), order.getNumber(),
                        order.getPublishedAt()))
                .orElse(null);
        List<OrderItem> items = published.stream().flatMap(order -> order.getItems().stream()).toList();
        return new PublicChangesDto(date, parity, UkrainianText.weekday(date.getDayOfWeek()), main, grouped(items));
    }

    private List<PublicChangesDto.GroupChanges> grouped(List<OrderItem> items) {
        Map<UUID, Change> changesById = changes.findAllById(items.stream().map(OrderItem::getChangeId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Change::getId, Function.identity()));
        Map<String, StudentGroup> groupsByCode = groups.findAllById(changesById.values().stream()
                        .map(Change::getGroupId).distinct().toList()).stream()
                .collect(Collectors.toMap(StudentGroup::getCode, Function.identity(), (first, second) -> first));

        Map<String, List<PublicChangesDto.ChangeView>> byGroup = new LinkedHashMap<>();
        for (OrderItem item : items) {
            Change change = item.getChangeId() == null ? null : changesById.get(item.getChangeId());
            byGroup.computeIfAbsent(item.getGroupCode(), code -> new ArrayList<>()).add(view(item, change));
        }
        Comparator<String> alphabetical = UkrainianText.collator();
        return byGroup.entrySet().stream()
                .map(entry -> new PublicChangesDto.GroupChanges(groupRef(entry.getKey(), groupsByCode),
                        entry.getValue()))
                .sorted(Comparator.comparingInt((PublicChangesDto.GroupChanges group) -> group.group().course())
                        .thenComparing(group -> group.group().code(), alphabetical))
                .toList();
    }

    private GroupRef groupRef(String code, Map<String, StudentGroup> known) {
        StudentGroup group = known.get(code);
        if (group == null) {
            group = groups.findByCode(code).orElse(null);
        }
        // рядок історичного розпорядження може посилатися на групу, якої вже немає в каталозі
        return group != null ? new GroupRef(group.getId(), group.getCode(), group.getCourse())
                : new GroupRef(null, code, Character.isDigit(code.charAt(0)) ? code.charAt(0) - '0' : 0);
    }

    private static PublicChangesDto.ChangeView view(OrderItem item, Change change) {
        return new PublicChangesDto.ChangeView(item.getPairLabel(), item.getRowType(),
                new PublicChangesDto.Side(item.getFromDiscipline(), item.getFromTeacher()), item.getReasonText(),
                new PublicChangesDto.Side(item.getToDiscipline(), item.getToTeacher()), item.getRoomText(),
                subgroupNote(item, change));
    }

    /** «1 підгрупа» for a row of one subgroup; merged rows of several subgroups need no note. */
    private static String subgroupNote(OrderItem item, Change change) {
        if (change == null || change.getSubgroupNumber() == 0) {
            return null;
        }
        boolean singleSubgroup = endsWithMark(item.getFromTeacher()) || endsWithMark(item.getToTeacher());
        return singleSubgroup ? change.getSubgroupNumber() + " підгрупа" : null;
    }

    private static boolean endsWithMark(String teacher) {
        return teacher != null && teacher.endsWith(OrderTexts.ONE_SUBGROUP);
    }
}
