package ua.vtc.rozklad.core.substitution.internal.effect;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.AppliedChanges;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangePublication;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeNotifications;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeStatusMachine;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeWorkflow;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Applies the confirmed changes of a published order to the dated lessons (DOMAIN §7). All
 * effects are planned first, then every replaced lesson is cancelled and only then the new lessons
 * are checked and inserted, so two changes may hand pairs over to each other within one slot.
 * Slot conflicts and absent substitutes are re-checked in code ({@code conflicts[]}) and slots once
 * more by the deferred database constraints.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ChangeApplier implements ChangePublication {

    private final ChangeRepository changes;
    private final LessonRepository lessons;
    private final ChangeEffectPlanner planner;
    private final DatedSlotChecker checker;
    private final ChangeWorkflow workflow;
    private final ChangeNotifications notifications;
    private final CatalogLookup catalog;
    private final AbsentSubstitutes absentSubstitutes;

    ChangeApplier(ChangeRepository changes, LessonRepository lessons, ChangeEffectPlanner planner,
                  DatedSlotChecker checker, ChangeWorkflow workflow, ChangeNotifications notifications,
                  CatalogLookup catalog, AbsentSubstitutes absentSubstitutes) {
        this.changes = changes;
        this.lessons = lessons;
        this.planner = planner;
        this.checker = checker;
        this.workflow = workflow;
        this.notifications = notifications;
        this.catalog = catalog;
        this.absentSubstitutes = absentSubstitutes;
    }

    @Override
    public AppliedChanges apply(Collection<UUID> changeIds, UUID orderId, String actor) {
        List<Change> toApply = changes.findAllById(changeIds).stream()
                .sorted(Comparator.comparing(Change::getDate).thenComparing(Change::getGroupId)
                        .thenComparingInt(Change::getPairFrom).thenComparing(Change::getCreatedAt))
                .toList();
        if (toApply.size() != Set.copyOf(changeIds).size()) {
            throw NotFoundException.of("Зміну", changeIds);
        }
        for (Change change : toApply) {
            if (change.getStatus() != ChangeStatus.CONFIRMED) {
                throw new ConflictException("Зміна вже не підтверджена (статус «%s»). Оновіть чернетку розпорядження."
                        .formatted(ChangeStatusMachine.label(change.getStatus())));
            }
        }
        requireOneDecisionPerLesson(toApply);
        // блокуємо дні груп у сталому порядку, щоб паралельні публікації не взаємоблокувалися
        toApply.stream()
                .map(change -> new GroupDay(change.getGroupId(), change.getDate()))
                .distinct()
                .sorted(Comparator.comparing(GroupDay::groupId).thenComparing(GroupDay::date))
                .forEach(groupDay -> lessons.lockGroupDay(groupDay.groupId(), groupDay.date()));

        List<Planned> plans = toApply.stream().map(change -> new Planned(change, planner.plan(change))).toList();
        Set<UUID> teacherIds = new HashSet<>();
        Set<UUID> cancelledIds = new HashSet<>();
        for (Planned plan : plans) {
            for (Lesson lesson : plan.effect().cancelled()) {
                if (lesson.isScheduled()) {
                    lesson.cancel();
                }
                cancelledIds.add(lesson.getId());
                teacherIds.add(lesson.getTeacherId());
            }
        }
        List<PlannedLesson> created = plans.stream().flatMap(plan -> plan.effect().created().stream()).toList();
        List<ConflictDto> conflicts = new ArrayList<>(checker.check(created, cancelledIds, null, false));
        // замінника могли зареєструвати відсутнім уже після підтвердження (DOMAIN §7)
        conflicts.addAll(absentSubstitutes.conflicts(toApply));
        if (!conflicts.isEmpty()) {
            throw ConflictException.schedule(conflicts);
        }
        insertLessons(plans);
        try {
            lessons.checkSlotConstraintsNow();
        } catch (DataIntegrityViolationException clash) {
            throw new ConflictException(
                    "Розклад щойно змінився паралельно з публікацією. Оновіть чернетку розпорядження і повторіть.");
        }

        for (Change change : toApply) {
            change.setOrderId(orderId);
            workflow.moveTo(change, ChangeStatus.APPLIED, actor, "Застосовано опублікованим розпорядженням");
            teacherIds.add(change.getOrigTeacherId());
            teacherIds.add(change.getNewTeacherId());
        }
        created.forEach(lesson -> teacherIds.add(lesson.teacherId()));
        changes.flush();
        toApply.forEach(notifications::pushUpdate);

        teacherIds.remove(null);
        return new AppliedChanges(toApply.stream().map(Change::getId).toList(),
                toApply.stream().map(Change::getGroupId).collect(Collectors.toSet()),
                teacherIds,
                toApply.stream().map(Change::getDate).collect(Collectors.toSet()));
    }

    /**
     * A lesson has one decision (DOMAIN §7). Creation and repeated confirmation requests enforce it;
     * this is the last line of defence, so an order never both cancels and replaces one pair.
     */
    private void requireOneDecisionPerLesson(List<Change> toApply) {
        Map<UUID, Change> deciders = new HashMap<>();
        List<ConflictDto> conflicts = new ArrayList<>();
        for (Change change : toApply) {
            for (UUID lessonId : Stream.of(change.getOriginalLessonId(), change.getSwapLessonId())
                    .filter(Objects::nonNull).distinct().toList()) {
                Change other = deciders.putIfAbsent(lessonId, change);
                if (other != null) {
                    String group = catalog.group(change.getGroupId()).getCode();
                    conflicts.add(ConflictDto.hard(ConflictKind.GROUP,
                            "Для пари групи %s (%d пара) підтверджено два рішення. Скасуйте одне з них."
                                    .formatted(group, change.getPairFrom()),
                            List.of(lessonId), change.getDate(), change.getPairFrom()));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw ConflictException.schedule(conflicts);
        }
    }

    private void insertLessons(List<Planned> plans) {
        Map<UUID, Room> rooms = catalog.rooms(plans.stream()
                .flatMap(plan -> plan.effect().created().stream())
                .map(PlannedLesson::roomId)
                .filter(Objects::nonNull)
                .toList());
        List<Lesson> inserted = new ArrayList<>();
        for (Planned plan : plans) {
            for (PlannedLesson lesson : plan.effect().created()) {
                Room room = lesson.roomId() == null ? null : rooms.get(lesson.roomId());
                // онлайн-заняття не займає аудиторію: індекс унікальності аудиторії його не бачить
                inserted.add(lesson.toLesson(plan.change().getId(),
                        !lesson.online() && (room == null || room.isExclusive())));
            }
        }
        lessons.saveAll(inserted);
    }

    private record Planned(Change change, ChangeEffect effect) {
    }

    private record GroupDay(UUID groupId, LocalDate date) {
    }
}
