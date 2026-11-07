package ua.vtc.rozklad.core.substitution.internal.change;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeHistory;
import ua.vtc.rozklad.core.substitution.ChangeHistoryRepository;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/** Maps changes to {@link ChangeDto} with references and history loaded in batches. */
@Component
public class ChangeViews {

    private final CatalogLookup catalog;
    private final ChangeHistoryRepository history;
    private final LessonRepository lessons;

    public ChangeViews(CatalogLookup catalog, ChangeHistoryRepository history, LessonRepository lessons) {
        this.catalog = catalog;
        this.history = history;
        this.lessons = lessons;
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
                change.getVersion())).toList();
    }
}
