package ua.vtc.rozklad.core.substitution.internal.change;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeHistory;
import ua.vtc.rozklad.core.substitution.ChangeHistoryRepository;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/** Maps changes to {@link ChangeDto} with references and history loaded in batches. */
@Component
public class ChangeViews {

    /** Room changes in between keep the substitute; a few steps cover every real chain. */
    private static final int MAX_CHAIN = 5;

    private final CatalogLookup catalog;
    private final ChangeHistoryRepository history;
    private final LessonRepository lessons;
    private final ChangeRepository changeRepository;

    public ChangeViews(CatalogLookup catalog, ChangeHistoryRepository history, LessonRepository lessons,
                       ChangeRepository changeRepository) {
        this.catalog = catalog;
        this.history = history;
        this.lessons = lessons;
        this.changeRepository = changeRepository;
    }

    public ChangeDto toDto(Change change) {
        return toDtos(List.of(change)).getFirst();
    }

    public List<ChangeDto> toDtos(List<Change> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<UUID, StudentGroup> groups = catalog.groups(changes.stream().map(Change::getGroupId).toList());
        Map<UUID, Teacher> teachers = catalog.teachers(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigTeacherId(), change.getNewTeacherId())).toList());
        Map<UUID, Discipline> disciplines = catalog.disciplines(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigDisciplineId(), change.getNewDisciplineId())).toList());
        Map<UUID, Room> rooms = catalog.rooms(changes.stream()
                .flatMap(change -> Stream.of(change.getOrigRoomId(), change.getNewRoomId())).toList());
        Map<UUID, List<ChangeDto.HistoryEntry>> historyByChange = history
                .findAllByChangeIdInOrderByAt(changes.stream().map(Change::getId).toList()).stream()
                .collect(Collectors.groupingBy(ChangeHistory::getChangeId, Collectors.mapping(
                        entry -> new ChangeDto.HistoryEntry(entry.getAt(), entry.getActor(), entry.getFromStatus(),
                                entry.getToStatus(), entry.getComment()), Collectors.toList())));
        Map<UUID, Integer> swapPairs = lessons.findAllById(changes.stream().map(Change::getSwapLessonId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Lesson::getId, Lesson::getPairNumber));
        Map<UUID, ChangeDto.Successor> successors = successors(changes);

        return changes.stream().map(change -> new ChangeDto(
                change.getId(),
                change.getKind(),
                change.getStatus(),
                change.getDate(),
                change.getPairFrom(),
                change.getPairTo(),
                CatalogLookup.ref(groups.get(change.getGroupId())),
                change.getSubgroupNumber(),
                new ChangeDto.Parties(
                        CatalogLookup.ref(disciplines.get(change.getOrigDisciplineId())),
                        CatalogLookup.ref(teachers.get(change.getOrigTeacherId())),
                        CatalogLookup.ref(rooms.get(change.getOrigRoomId()))),
                new ChangeDto.Parties(
                        CatalogLookup.ref(disciplines.get(change.getNewDisciplineId())),
                        CatalogLookup.ref(teachers.get(change.getNewTeacherId())),
                        CatalogLookup.ref(rooms.get(change.getNewRoomId()))),
                change.getReasonCode(),
                change.getReasonText(),
                change.getAbsenceId(),
                change.getConfirmDeadline(),
                change.getRespondedAt(),
                change.getOrderId(),
                historyByChange.getOrDefault(change.getId(), List.of()),
                change.getApprovalMode(),
                change.getOriginalLessonId(),
                change.getSwapLessonId(),
                change.getSwapLessonId() == null ? null : swapPairs.get(change.getSwapLessonId()),
                change.printedReason(),
                change.getResponseComment(),
                change.getRemindedAt(),
                change.getCreatedBy(),
                change.getCreatedAt(),
                change.getVersion(),
                successors.get(change.getId()),
                change.getTeacherAnswer())).toList();
    }

    /**
     * For applied changes: the applied change that later decided about the lesson they created
     * (cancelled it, gave it to another teacher, moved it). A room change keeps the substitute, so
     * the lesson it created is followed further.
     */
    private Map<UUID, ChangeDto.Successor> successors(List<Change> changes) {
        Map<UUID, UUID> rootOf = changes.stream().filter(change -> change.getStatus() == ChangeStatus.APPLIED)
                .collect(Collectors.toMap(Change::getId, Change::getId, (first, second) -> first));
        Map<UUID, ChangeDto.Successor> found = new HashMap<>();
        for (int step = 0; step < MAX_CHAIN && !rootOf.isEmpty(); step++) {
            Map<UUID, UUID> lessonRoot = new HashMap<>();
            for (Lesson created : lessons.findAllByChangeIdIn(rootOf.keySet())) {
                lessonRoot.put(created.getId(), rootOf.get(created.getChangeId()));
            }
            if (lessonRoot.isEmpty()) {
                break;
            }
            Map<UUID, UUID> next = new HashMap<>();
            changeRepository.findAllByOriginalLessonIdIn(lessonRoot.keySet()).stream()
                    .filter(later -> later.getStatus() == ChangeStatus.APPLIED)
                    .sorted(Comparator.comparing(Change::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(later -> {
                        UUID root = lessonRoot.get(later.getOriginalLessonId());
                        if (later.getKind() == ChangeKind.ROOM_CHANGE) {
                            next.putIfAbsent(later.getId(), root);
                        } else {
                            found.putIfAbsent(root, new ChangeDto.Successor(later.getId(), later.getKind(),
                                    later.getOrderId()));
                        }
                    });
            rootOf = next;
        }
        return found;
    }
}
