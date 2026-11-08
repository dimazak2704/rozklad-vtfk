package ua.vtc.rozklad.core.order.internal.publish;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.internal.draft.ChangeRows;
import ua.vtc.rozklad.core.order.internal.draft.OrderDrafts;
import ua.vtc.rozklad.core.order.internal.draft.OrderRowComposer;
import ua.vtc.rozklad.core.order.internal.draft.OrderTexts;
import ua.vtc.rozklad.core.order.internal.numbering.OrderNumberingService;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.order.internal.view.OrderViews;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.AppliedChanges;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangePublication;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Publication of an order (DOMAIN §7–§8) in one transaction: the next number of the semester (not
 * below its first number, {@link OrderNumberingService}) is taken under an advisory lock (no gaps, no duplicates under concurrent publications), the
 * confirmed changes of the draft are applied to the dated lessons (conflicts → 409, nothing is
 * published), and {@code order.published} with the full payload plus {@code schedule.changed}
 * go to the outbox. A draft whose rows no longer match the confirmed changes is stale (409):
 * only what the dispatcher has seen gets published.
 */
@Service
@Transactional
public class OrderPublication {

    static final String STALE_DRAFT = "Чернетка застаріла: після її формування змінилися підтверджені зміни. "
            + "Оновіть чернетку, перевірте її і опублікуйте ще раз.";

    private final OrderDocumentRepository orders;
    private final SemesterRepository semesters;
    private final ChangeRepository changes;
    private final ChangeRows rows;
    private final ChangePublication changePublication;
    private final OrderNumberingService numbering;
    private final StudentGroupRepository groups;
    private final TeacherRepository teachers;
    private final ApplicationEventPublisher events;
    private final SseAfterCommit sse;
    private final Clock clock;

    public OrderPublication(OrderDocumentRepository orders, SemesterRepository semesters, ChangeRepository changes,
                            ChangeRows rows, ChangePublication changePublication, OrderNumberingService numbering,
                            StudentGroupRepository groups,
                            TeacherRepository teachers, ApplicationEventPublisher events, SseAfterCommit sse,
                            Clock clock) {
        this.orders = orders;
        this.semesters = semesters;
        this.changes = changes;
        this.rows = rows;
        this.changePublication = changePublication;
        this.numbering = numbering;
        this.groups = groups;
        this.teachers = teachers;
        this.events = events;
        this.sse = sse;
        this.clock = clock;
    }

    public OrderDto publish(UUID orderId, String actor) {
        UUID semesterId = orders.findSemesterIdById(orderId)
                .orElseThrow(() -> NotFoundException.of("Розпорядження", orderId));
        // замок нумерації береться до читання розпорядження: так паралельна публікація бачить свіжий стан
        orders.lockNumbering(semesterId);
        OrderDocument order = orders.findWithItemsById(orderId)
                .orElseThrow(() -> NotFoundException.of("Розпорядження", orderId));
        orders.lockDate(semesterId, order.getDate());
        if (!order.isDraft()) {
            throw new BusinessRuleException("ORDER_ALREADY_PUBLISHED", "Розпорядження вже опубліковане.");
        }
        List<Change> confirmed = changes.findAllByDateAndStatusIn(order.getDate(), Set.of(ChangeStatus.CONFIRMED));
        if (!OrderDrafts.sameRows(order, OrderRowComposer.compose(rows.of(confirmed)))) {
            throw new ConflictException(STALE_DRAFT);
        }
        if (order.getKind() == OrderKind.AMENDMENT && confirmed.isEmpty()) {
            throw new BusinessRuleException("AMENDMENT_EMPTY", "У доповненні немає нових змін — публікувати нічого.");
        }
        AppliedChanges applied = confirmed.isEmpty()
                ? new AppliedChanges(List.of(), Set.of(), Set.of(), Set.of())
                : changePublication.apply(confirmed.stream().map(Change::getId).toList(), order.getId(), actor);

        int number = numbering.nextNumber(semesterId);
        if (order.getKind() == OrderKind.ORDER) {
            order.setTitle(OrderTexts.mainTitle(number));
        }
        order.markPublished(number, clock.instant(), actor);
        orders.flush();

        Semester semester = semesters.findById(semesterId).orElseThrow(() -> NotFoundException.of("Семестр", semesterId));
        OrderPayload payload = OrderViews.toPayload(order, semester.getName(), affectedGroups(applied),
                affectedTeachers(applied));
        events.publishEvent(new OrderPublished(EventEnvelope.create(clock), payload));
        OrderDto dto = OrderViews.toDto(order);
        sse.broadcast(SseEventTypes.ORDER_PUBLISHED, new OrderDto.Summary(order.getId(), order.getNumber(),
                order.getDate(), order.getKind(), order.getStatus(), order.getItems().size(), order.getPublishedAt()));
        if (!applied.changeIds().isEmpty()) {
            ScheduleChanged scheduleChanged = new ScheduleChanged(EventEnvelope.create(clock),
                    applied.dates().stream().sorted().toList(), List.copyOf(applied.groupIds()),
                    List.copyOf(applied.teacherIds()), ScheduleChangeReason.ORDER_PUBLISHED);
            events.publishEvent(scheduleChanged);
            sse.broadcast(SseEventTypes.SCHEDULE_CHANGED, scheduleChanged);
        }
        return dto;
    }

    private List<OrderPayload.AffectedGroup> affectedGroups(AppliedChanges applied) {
        Comparator<String> alphabetical = UkrainianText.collator();
        return groups.findAllById(applied.groupIds()).stream()
                .sorted(Comparator.comparingInt(StudentGroup::getCourse)
                        .thenComparing(StudentGroup::getCode, alphabetical))
                .map(group -> new OrderPayload.AffectedGroup(group.getId(), group.getCode()))
                .toList();
    }

    private List<OrderPayload.AffectedTeacher> affectedTeachers(AppliedChanges applied) {
        Comparator<String> alphabetical = UkrainianText.collator();
        return teachers.findAllById(applied.teacherIds()).stream()
                .map(teacher -> new OrderPayload.AffectedTeacher(teacher.getId(), teacher.getShortName()))
                .sorted(Comparator.comparing(OrderPayload.AffectedTeacher::name, alphabetical))
                .toList();
    }
}
