package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.internal.draft.OrderTexts;
import ua.vtc.rozklad.core.order.internal.support.OrderCalendar;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Public changes of a date, taken from the published orders only (the document of record): the
 * main order first, then its amendments, rows grouped by group and ordered by course and code.
 * A room-change row prints only group, pair and room; for the site it also names the scheduled
 * lesson and its old room, taken from the change behind the row.
 */
@Service
@Transactional(readOnly = true)
class PublicChanges {

    private final OrderDocumentRepository orders;
    private final ChangeRepository changes;
    private final StudentGroupRepository groups;
    private final OrderCalendar calendar;
    private final DisciplineRepository disciplines;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;

    PublicChanges(OrderDocumentRepository orders, ChangeRepository changes, StudentGroupRepository groups,
                  OrderCalendar calendar, DisciplineRepository disciplines, TeacherRepository teachers,
                  RoomRepository rooms) {
        this.orders = orders;
        this.changes = changes;
        this.groups = groups;
        this.calendar = calendar;
        this.disciplines = disciplines;
        this.teachers = teachers;
        this.rooms = rooms;
    }

    PublicChangesDto of(LocalDate date) {
        Parity parity = calendar.parityOrNull(date);
        List<OrderDocument> published = orders.findAllByDateAndStatusOrderByCreatedAt(date, OrderStatus.PUBLISHED)
                .stream()
                .sorted(Comparator.comparing((OrderDocument order) -> order.getKind() == OrderKind.AMENDMENT)
                        .thenComparing(OrderDocument::getNumber))
                .toList();
        PublicChangesDto.PublishedOrder main = published.stream()
                .filter(order -> order.getKind() == OrderKind.ORDER)
                .findFirst()
                .map(PublicChanges::summary)
                .orElse(null);
        List<PublicChangesDto.PublishedOrder> amendments = published.stream()
                .filter(order -> order.getKind() == OrderKind.AMENDMENT)
                .map(PublicChanges::summary)
                .toList();
        List<PrintedRow> rows = published.stream()
                .flatMap(order -> order.getItems().stream().map(item -> new PrintedRow(item, order.getNumber())))
                .toList();
        return new PublicChangesDto(date, parity, calendar.isStudyDay(date), UkrainianText.weekday(date.getDayOfWeek()),
                main, amendments, grouped(rows));
    }

    private static PublicChangesDto.PublishedOrder summary(OrderDocument order) {
        return new PublicChangesDto.PublishedOrder(order.getId(), order.getNumber(), order.getPublishedAt(),
                order.isImported());
    }

    private List<PublicChangesDto.GroupChanges> grouped(List<PrintedRow> rows) {
        List<OrderItem> items = rows.stream().map(PrintedRow::item).toList();
        Map<UUID, Change> changesById = changes.findAllById(items.stream().map(OrderItem::getChangeId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Change::getId, Function.identity()));
        Map<String, StudentGroup> groupsByCode = groups.findAllById(changesById.values().stream()
                        .map(Change::getGroupId).distinct().toList()).stream()
                .collect(Collectors.toMap(StudentGroup::getCode, Function.identity(), (first, second) -> first));

        RoomChangeLessons roomChanges = roomChangeLessons(rows, changesById);

        Map<String, List<PublicChangesDto.ChangeView>> byGroup = new LinkedHashMap<>();
        for (PrintedRow row : rows) {
            OrderItem item = row.item();
            Change change = item.getChangeId() == null ? null : changesById.get(item.getChangeId());
            byGroup.computeIfAbsent(item.getGroupCode(), code -> new ArrayList<>())
                    .add(view(item, change, row.orderNumber(), roomChanges));
        }
        Comparator<String> alphabetical = UkrainianText.collator();
        return byGroup.entrySet().stream()
                .map(entry -> new PublicChangesDto.GroupChanges(groupRef(entry.getKey(), groupsByCode),
                        entry.getValue()))
                .sorted(Comparator.comparingInt((PublicChangesDto.GroupChanges group) -> group.group().course())
                        .thenComparing(group -> group.group().code(), alphabetical))
                .toList();
    }

    private PublicChangesDto.ChangeGroup groupRef(String code, Map<String, StudentGroup> known) {
        StudentGroup group = known.get(code);
        if (group == null) {
            group = groups.findByCode(code).orElse(null);
        }
        // рядок історичного розпорядження може посилатися на групу, якої вже немає в каталозі
        return group != null ? new PublicChangesDto.ChangeGroup(group.getId(), group.getCode(), group.getCourse())
                : new PublicChangesDto.ChangeGroup(null, code, Character.isDigit(code.charAt(0)) ? code.charAt(0) - '0' : 0);
    }

    private static PublicChangesDto.ChangeView view(OrderItem item, Change change, Integer orderNumber,
                                                    RoomChangeLessons roomChanges) {
        PublicChangesDto.Side from = new PublicChangesDto.Side(item.getFromDiscipline(), item.getFromTeacher());
        String previousRoom = null;
        if (describesScheduledLesson(item, change)) {
            from = new PublicChangesDto.Side(roomChanges.discipline(change.getOrigDisciplineId()),
                    roomChanges.teacher(change.getOrigTeacherId()));
            previousRoom = roomChanges.room(change.getOrigRoomId());
        }
        return new PublicChangesDto.ChangeView(item.getPairLabel(), item.getRowType(), from, item.getReasonText(),
                new PublicChangesDto.Side(item.getToDiscipline(), item.getToTeacher()), item.getRoomText(),
                subgroupNote(item, change), orderNumber, previousRoom);
    }

    /**
     * A room-change row of one change: rows merged from several subgroups («304/309») and imported
     * paper rows (no change) keep only what the order prints.
     */
    private static boolean describesScheduledLesson(OrderItem item, Change change) {
        return item.getRowType() == OrderRowType.ROOM_CHANGE && change != null
                && (item.getRoomText() == null || !item.getRoomText().contains("/"));
    }

    /** Names of the scheduled lessons behind the room-change rows, read in one query per catalog. */
    private RoomChangeLessons roomChangeLessons(List<PrintedRow> rows, Map<UUID, Change> changesById) {
        List<Change> roomChanges = rows.stream()
                .map(PrintedRow::item)
                .filter(item -> item.getRowType() == OrderRowType.ROOM_CHANGE && item.getChangeId() != null)
                .map(item -> changesById.get(item.getChangeId()))
                .filter(Objects::nonNull)
                .toList();
        if (roomChanges.isEmpty()) {
            return RoomChangeLessons.NONE;
        }
        return new RoomChangeLessons(
                names(disciplines.findAllById(ids(roomChanges.stream().map(Change::getOrigDisciplineId))),
                        Discipline::getId, Discipline::getShortName),
                names(teachers.findAllById(ids(roomChanges.stream().map(Change::getOrigTeacherId))),
                        Teacher::getId, Teacher::getDisplayName),
                names(rooms.findAllById(ids(roomChanges.stream().map(Change::getOrigRoomId))),
                        Room::getId, Room::getName));
    }

    private static List<UUID> ids(Stream<UUID> ids) {
        return ids.filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, String> names(List<T> entities, Function<T, UUID> id, Function<T, String> name) {
        // a teacher may have no display name: keep the null instead of failing the whole page
        Map<UUID, String> byId = new HashMap<>();
        entities.forEach(entity -> byId.put(id.apply(entity), name.apply(entity)));
        return byId;
    }

    /** Short discipline names, teacher display names and room names by id. */
    private record RoomChangeLessons(Map<UUID, String> disciplines, Map<UUID, String> teachers,
                                     Map<UUID, String> rooms) {

        static final RoomChangeLessons NONE = new RoomChangeLessons(Map.of(), Map.of(), Map.of());

        String discipline(UUID id) {
            return id == null ? null : disciplines.get(id);
        }

        String teacher(UUID id) {
            return id == null ? null : teachers.get(id);
        }

        String room(UUID id) {
            return id == null ? null : rooms.get(id);
        }
    }

    /** A row with the number of the order (or amendment) it is printed in. */
    private record PrintedRow(OrderItem item, Integer orderNumber) {
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
