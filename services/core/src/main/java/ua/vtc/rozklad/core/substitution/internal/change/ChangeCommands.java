package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.effect.AbsentSubstitutes;
import ua.vtc.rozklad.core.substitution.internal.effect.ChangeEffect;
import ua.vtc.rozklad.core.substitution.internal.effect.ChangeEffectPlanner;
import ua.vtc.rozklad.core.substitution.internal.effect.DatedSlotChecker;
import ua.vtc.rozklad.core.substitution.internal.effect.SubstituteRooms;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Dispatcher commands on changes: create (with approval rules and a conflict check), edit the room
 * or reason, cancel, ask the teacher again, confirm on the teacher's behalf. Decisions about one
 * date are serialized with {@code changeRepository.lockDecisionsOf} (a teacher or a room is shared
 * by all groups of the date), then the group-day lessons are locked with
 * {@code lessonRepository.lockGroupDay}; concurrent edits of one change are caught by optimistic
 * locking ({@code version} is required in PUT). Every transition of an existing change takes the
 * date lock before it touches the change row: the cabinet, the bot and the dispatcher then queue
 * up in one order instead of deadlocking, and the loser's stale change fails the version check
 * (409 stale-data). A cover of another discipline (REPLACE_DISCIPLINE) without {@code newRoomId}
 * goes to the substitute's usual room when it is free ({@link SubstituteRooms}), else the lesson
 * keeps its room; the response tells which room was chosen ({@code new.room}).
 */
@Service
@Transactional
public class ChangeCommands {

    private final ChangeRepository changes;
    private final AbsenceRepository absences;
    private final LessonRepository lessons;
    private final CatalogLookup catalog;
    private final SubstitutionCalendar calendar;
    private final ChangeEffectPlanner planner;
    private final DatedSlotChecker checker;
    private final ChangeWorkflow workflow;
    private final ChangeNotifications notifications;
    private final ChangeViews views;
    private final AbsentSubstitutes absentSubstitutes;
    private final SubstituteRooms substituteRooms;
    private final Clock clock;

    public ChangeCommands(ChangeRepository changes, AbsenceRepository absences, LessonRepository lessons,
                          CatalogLookup catalog, SubstitutionCalendar calendar, ChangeEffectPlanner planner,
                          DatedSlotChecker checker, ChangeWorkflow workflow, ChangeNotifications notifications,
                          ChangeViews views, AbsentSubstitutes absentSubstitutes, SubstituteRooms substituteRooms,
                          Clock clock) {
        this.changes = changes;
        this.absences = absences;
        this.lessons = lessons;
        this.catalog = catalog;
        this.calendar = calendar;
        this.planner = planner;
        this.checker = checker;
        this.workflow = workflow;
        this.notifications = notifications;
        this.views = views;
        this.absentSubstitutes = absentSubstitutes;
        this.substituteRooms = substituteRooms;
        this.clock = clock;
    }

    /**
     * {@code POST /api/admin/changes/batch}: creates the changes in the given order in one
     * transaction (e.g. a cancel and its make-up pair, or every proposal of a day plan). Any 400 or
     * 409 rolls back the whole batch; field errors name the item ({@code changes[1].newTeacherId}).
     * The dates are locked in ascending order first, so two batches cannot deadlock.
     */
    public List<ChangeDto> createAll(List<CreateChangeRequest> requests, String actor) {
        requests.stream().map(CreateChangeRequest::date).filter(Objects::nonNull).distinct().sorted()
                .forEach(changes::lockDecisionsOf);
        List<ChangeDto> created = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            try {
                created.add(create(requests.get(index), actor));
            } catch (FieldValidationException error) {
                String prefix = "changes[" + index + "].";
                throw new FieldValidationException(error.getErrors().stream()
                        .map(field -> new FieldErrorDto(prefix + field.field(), field.message()))
                        .toList());
            }
        }
        return created;
    }

    public ChangeDto create(CreateChangeRequest request, String actor) {
        StudentGroup group = catalog.group(request.groupId(), "groupId");
        // спершу блокуємо дату (викладач і аудиторія спільні для всіх груп), потім день групи,
        // і лише тоді читаємо заняття та відкриті зміни: так паралельні рішення не перетинаються
        changes.lockDecisionsOf(request.date());
        List<Lesson> groupDay = lessons.lockGroupDay(group.getId(), request.date());
        Change change = new Change(request.kind(), request.date(), request.pairFrom(), request.lastPair(),
                group.getId(), cohortOf(request, groupDay), actor);
        Lesson original = null;
        if (request.originalLessonId() != null) {
            original = lessonOfDay(request.originalLessonId(), groupDay, request);
            change.setOriginal(original.getId(), original.getDisciplineId(), original.getTeacherId(),
                    original.getRoomId());
        } else {
            requireClassesFor(group, request.date());
            requireKnownSubgroup(group, change.getSubgroupNumber());
        }
        fillReplacement(change, request, groupDay);
        if (change.getKind() == ChangeKind.REPLACE_DISCIPLINE && request.newRoomId() == null && original != null) {
            // аудиторія належить викладачу: група йде до замінника, якщо його аудиторія вільна
            substituteRooms.usualFreeRoom(original, change.getNewTeacherId(), change.getNewDisciplineId(), null)
                    .ifPresent(room -> change.setReplacement(change.getNewDisciplineId(), change.getNewTeacherId(),
                            room));
        }
        change.setReason(request.reasonCode(), blankToNull(request.reasonText()));
        if (request.absenceId() != null) {
            change.setAbsenceId(activeAbsenceFor(request.absenceId(), change).getId());
        }

        ChangeEffect effect = planner.plan(change);
        requireNoCompetingDecision(change, effect);
        requireFreeSlot(change, effect);
        changes.save(change);
        workflow.open(change, actor);
        return finish(change);
    }

    public ChangeDto update(UUID id, UpdateChangeRequest request, String actor) {
        Change change = find(id);
        // version обов'язкова (400 на рівні контролера); без неї зміну не перезаписуємо мовчки
        if (request.version() == null || request.version() != change.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Change.class, id);
        }
        if (!change.isOpen() && change.getStatus() != ChangeStatus.DECLINED) {
            throw new BusinessRuleException("CHANGE_NOT_EDITABLE", "Зміну зі статусом «%s» редагувати не можна."
                    .formatted(ChangeStatusMachine.label(change.getStatus())));
        }
        if (change.getKind() == ChangeKind.DISCIPLINE_FINISHED
                && request.reasonCode() != ReasonCode.FINISHED) {
            throw new BusinessRuleException("CHANGE_INVALID", "Для вичитаної дисципліни причина — «Вичит.».");
        }
        if (change.getKind() == ChangeKind.ROOM_CHANGE && request.newRoomId() == null) {
            throw new BusinessRuleException("CHANGE_INVALID", "Для заміни аудиторії вкажіть нову аудиторію.");
        }
        if (change.getKind() == ChangeKind.CANCEL && request.newRoomId() != null) {
            throw new BusinessRuleException("CHANGE_INVALID", "Скасована пара не має аудиторії.");
        }
        if (request.newRoomId() != null) {
            catalog.room(request.newRoomId(), "newRoomId");
        }
        changes.lockDecisionsOf(change.getDate());
        lessons.lockGroupDay(change.getGroupId(), change.getDate());
        change.setReplacement(change.getNewDisciplineId(), change.getNewTeacherId(), request.newRoomId());
        change.setReason(request.reasonCode(), blankToNull(request.reasonText()));
        if (change.isOpen()) {
            requireFreeSlot(change, planner.plan(change));
        }
        return finish(change);
    }

    /** DELETE: cancels the change; cancelling a cancelled change is a no-op. */
    public void cancel(UUID id, String actor) {
        Change change = find(id);
        changes.lockDecisionsOf(change.getDate());
        if (change.getStatus() == ChangeStatus.CANCELLED) {
            return;
        }
        if (change.getStatus() == ChangeStatus.APPLIED) {
            throw new BusinessRuleException("CHANGE_ALREADY_APPLIED",
                    "Зміна вже застосована в опублікованому розпорядженні. Щоб повернути пару, створіть нову зміну.");
        }
        workflow.moveTo(change, ChangeStatus.CANCELLED, actor, "Скасовано диспетчером");
        finish(change);
    }

    /**
     * Asks the assigned teacher again. A declined (or draft) change is not a live decision, so it
     * goes through the same checks as a new one: another decision may have been made for the lesson
     * meanwhile, and the slot may have been taken.
     */
    public ChangeDto requestConfirmation(UUID id, String actor) {
        Change change = find(id);
        changes.lockDecisionsOf(change.getDate());
        if (change.getStatus() == ChangeStatus.DRAFT || change.getStatus() == ChangeStatus.DECLINED) {
            lessons.lockGroupDay(change.getGroupId(), change.getDate());
            ChangeEffect effect = planner.plan(change);
            requireNoCompetingDecision(change, effect);
            requireFreeSlot(change, effect);
        } else {
            requireSubstitutePresent(change);
        }
        workflow.requestConfirmation(change, actor);
        return finish(change);
    }

    /** The dispatcher confirms instead of the teacher (e.g. after a phone call). */
    public ChangeDto confirm(UUID id, String actor) {
        Change change = find(id);
        changes.lockDecisionsOf(change.getDate());
        if (change.getStatus() == ChangeStatus.DECLINED) {
            throw new BusinessRuleException("CHANGE_DECLINED",
                    "Викладач відмовився від цієї заміни. Надішліть запит ще раз або оберіть іншого викладача.");
        }
        requireSubstitutePresent(change);
        workflow.moveTo(change, ChangeStatus.CONFIRMED, actor, "Підтверджено диспетчером");
        return finish(change);
    }

    /** Cancels the not yet applied changes of an absence that match {@code which}. */
    public void cancelForAbsence(UUID absenceId, Predicate<Change> which, String actor, String comment) {
        changes.findAllByAbsenceId(absenceId).stream()
                .filter(change -> change.isOpen() || change.getStatus() == ChangeStatus.DECLINED)
                .filter(which)
                .forEach(change -> {
                    changes.lockDecisionsOf(change.getDate());
                    workflow.moveTo(change, ChangeStatus.CANCELLED, actor, comment);
                    finish(change);
                });
    }

    /**
     * Hard conflicts of the lessons the change creates (busy teacher, cohort or room, slots promised
     * by other open changes) plus a newly assigned teacher who is absent at the slot (DOMAIN §7).
     */
    private void requireFreeSlot(Change change, ChangeEffect effect) {
        List<ConflictDto> conflicts = new ArrayList<>(checker.check(effect.created(), effect.cancelledIds(),
                change.getId(), true));
        conflicts.addAll(absentSubstitutes.conflicts(change));
        if (!conflicts.isEmpty()) {
            throw ConflictException.schedule(conflicts);
        }
    }

    /** A substitute registered absent after the change was made cannot be confirmed (409 UNAVAILABLE). */
    private void requireSubstitutePresent(Change change) {
        List<ConflictDto> conflicts = absentSubstitutes.conflicts(change);
        if (!conflicts.isEmpty()) {
            throw ConflictException.schedule(conflicts);
        }
    }

    private ChangeDto finish(Change change) {
        changes.flush();
        notifications.pushUpdate(change);
        return views.toDto(change);
    }

    private Change find(UUID id) {
        return changes.findById(id).orElseThrow(() -> NotFoundException.of("Зміну", id));
    }

    private static int cohortOf(CreateChangeRequest request, List<Lesson> groupDay) {
        if (request.originalLessonId() != null) {
            return groupDay.stream().filter(lesson -> lesson.getId().equals(request.originalLessonId()))
                    .findFirst().map(Lesson::getSubgroupNumber)
                    .orElse(request.subgroup() == null ? 0 : request.subgroup());
        }
        return request.subgroup() == null ? 0 : request.subgroup();
    }

    /** The original lesson must belong to the requested group, date and pair and still take place. */
    private Lesson lessonOfDay(UUID lessonId, List<Lesson> groupDay, CreateChangeRequest request) {
        Lesson lesson = groupDay.stream().filter(candidate -> candidate.getId().equals(lessonId)).findFirst()
                .orElse(null);
        if (lesson == null) {
            if (!lessons.existsById(lessonId)) {
                throw FieldValidationException.of("originalLessonId", "Заняття не знайдено.");
            }
            throw mismatch();
        }
        if (lesson.getPairNumber() != request.pairFrom()
                || (request.subgroup() != null && request.subgroup() != lesson.getSubgroupNumber())) {
            throw mismatch();
        }
        if (!lesson.isScheduled()) {
            throw new ConflictException("Це заняття вже скасоване або змінене опублікованим розпорядженням.");
        }
        return lesson;
    }

    private static BusinessRuleException mismatch() {
        return new BusinessRuleException("CHANGE_LESSON_MISMATCH",
                "Дата, пара, група або підгрупа не збігаються з вибраним заняттям.");
    }

    private void requireClassesFor(StudentGroup group, LocalDate date) {
        calendar.requireStudyDate(date);
        calendar.noClassesReason(date, group)
                .ifPresent(reason -> {
                    throw new BusinessRuleException("NO_CLASSES",
                            "У групи %s %s занять немає: %s.".formatted(group.getCode(),
                                    date.format(SubstitutionCalendar.DATE), reason));
                });
    }

    /**
     * A pair without an original lesson (additional pair, block) may name a subgroup only if the
     * group has it: a subgroup of the catalog or one the group's timetable uses.
     */
    private void requireKnownSubgroup(StudentGroup group, int subgroup) {
        if (subgroup == 0 || catalog.hasSubgroup(group.getId(), subgroup)
                || lessons.existsByGroupIdAndSubgroupNumber(group.getId(), subgroup)) {
            return;
        }
        throw FieldValidationException.of("subgroup", "У групи %s немає підгрупи %d.".formatted(group.getCode(),
                subgroup));
    }

    private void fillReplacement(Change change, CreateChangeRequest request, List<Lesson> groupDay) {
        UUID room = request.newRoomId() == null ? null : catalog.room(request.newRoomId(), "newRoomId").getId();
        switch (change.getKind()) {
            case REPLACE_TEACHER -> {
                UUID teacher = catalog.teacher(request.newTeacherId(), "newTeacherId").getId();
                if (teacher.equals(change.getOrigTeacherId())) {
                    throw new BusinessRuleException("CHANGE_SAME_TEACHER",
                            "Новий викладач збігається з тим, кого замінюють.");
                }
                if (request.newDisciplineId() != null && !request.newDisciplineId().equals(change.getOrigDisciplineId())) {
                    throw new BusinessRuleException("CHANGE_INVALID",
                            "Заміна викладача не змінює дисципліну. Для іншої дисципліни оберіть «Заміна дисципліни».");
                }
                change.setReplacement(change.getOrigDisciplineId(), teacher, room);
            }
            case REPLACE_DISCIPLINE -> {
                UUID discipline = catalog.discipline(request.newDisciplineId(), "newDisciplineId").getId();
                if (discipline.equals(change.getOrigDisciplineId())) {
                    throw new BusinessRuleException("CHANGE_INVALID",
                            "Дисципліна та сама. Для іншого викладача оберіть «Заміна викладача».");
                }
                change.setReplacement(discipline, catalog.teacher(request.newTeacherId(), "newTeacherId").getId(), room);
            }
            case ROOM_CHANGE -> {
                if (room.equals(change.getOrigRoomId())) {
                    throw new BusinessRuleException("CHANGE_INVALID", "Нова аудиторія збігається з поточною.");
                }
                change.setReplacement(null, null, room);
            }
            case CANCEL -> change.setReplacement(null, null, null);
            case DISCIPLINE_FINISHED -> change.setReplacement(
                    request.newDisciplineId() == null ? null : catalog.discipline(request.newDisciplineId(), "newDisciplineId").getId(),
                    request.newTeacherId() == null ? null : catalog.teacher(request.newTeacherId(), "newTeacherId").getId(), room);
            case SWAP -> {
                Lesson moved = swappedLesson(request, change, groupDay);
                change.setSwapLessonId(moved.getId());
                change.setReplacement(moved.getDisciplineId(), moved.getTeacherId(), room);
            }
            case MERGE_SUBGROUPS -> {
                Lesson partner = mergePartner(request, change, groupDay);
                change.setSwapLessonId(partner.getId());
                // група приходить до викладача іншої підгрупи: його аудиторія, якщо не вказано іншу
                change.setReplacement(partner.getDisciplineId(), partner.getTeacherId(),
                        room != null ? room : partner.getRoomId());
            }
            case ADDITIONAL, BLOCK -> change.setReplacement(catalog.discipline(request.newDisciplineId(), "newDisciplineId").getId(),
                    request.newTeacherId() == null ? null : catalog.teacher(request.newTeacherId(), "newTeacherId").getId(), room);
        }
    }

    /**
     * SWAP moves another lesson of the same cohort into the slot: a later one, or for a future day
     * also an earlier one (DOMAIN §7); its teacher must not be the absent one.
     */
    private Lesson swappedLesson(CreateChangeRequest request, Change change, List<Lesson> groupDay) {
        Lesson moved = groupDay.stream().filter(lesson -> lesson.getId().equals(request.swapLessonId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("SWAP_INVALID",
                        "Переставити можна лише пару тієї самої групи в той самий день."));
        boolean futureDay = change.getDate().isAfter(LocalDate.now(clock));
        boolean allowedPair = moved.getPairNumber() > change.getPairFrom()
                || (futureDay && moved.getPairNumber() < change.getPairFrom());
        if (!moved.isScheduled() || !allowedPair || moved.getSubgroupNumber() != change.getSubgroupNumber()) {
            throw new BusinessRuleException("SWAP_INVALID", futureDay
                    ? "Переставити можна лише іншу пару тієї самої (під)групи."
                    : "Переставити можна лише одну з наступних пар тієї самої (під)групи.");
        }
        if (moved.getTeacherId() == null || moved.getTeacherId().equals(change.getOrigTeacherId())) {
            throw new BusinessRuleException("SWAP_INVALID", "Цю пару веде той самий відсутній викладач.");
        }
        return moved;
    }

    /**
     * MERGE_SUBGROUPS: the lesson of the same discipline for another subgroup at the same pair,
     * taught by someone else than the absent teacher; together they become one whole-group lesson.
     */
    private Lesson mergePartner(CreateChangeRequest request, Change change, List<Lesson> groupDay) {
        Lesson partner = groupDay.stream().filter(lesson -> lesson.getId().equals(request.swapLessonId()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("MERGE_INVALID",
                        "Об'єднати можна лише з парою іншої підгрупи тієї самої групи в той самий день."));
        if (change.getSubgroupNumber() == 0) {
            throw new BusinessRuleException("MERGE_INVALID", "Ця пара вже для всієї групи, об'єднувати нічого.");
        }
        if (!partner.isScheduled() || partner.getPairNumber() != change.getPairFrom()
                || partner.getSubgroupNumber() == 0 || partner.getSubgroupNumber() == change.getSubgroupNumber()
                || !partner.getDisciplineId().equals(change.getOrigDisciplineId())) {
            throw new BusinessRuleException("MERGE_INVALID",
                    "Об'єднати можна лише з тією самою дисципліною іншої підгрупи на цій самій парі.");
        }
        if (partner.getTeacherId() == null || partner.getTeacherId().equals(change.getOrigTeacherId())) {
            throw new BusinessRuleException("MERGE_INVALID", "Пару іншої підгрупи веде той самий відсутній викладач.");
        }
        return partner;
    }

    private Absence activeAbsenceFor(UUID absenceId, Change change) {
        Absence absence = absences.findById(absenceId)
                .orElseThrow(() -> FieldValidationException.of("absenceId", "Відсутність не знайдено."));
        if (!absence.isActive()) {
            throw new BusinessRuleException("ABSENCE_CANCELLED", "Ця відсутність уже скасована.");
        }
        if (change.getOrigTeacherId() != null && !absence.getTeacherId().equals(change.getOrigTeacherId())) {
            throw new BusinessRuleException("ABSENCE_MISMATCH", "Відсутність належить іншому викладачу.");
        }
        return absence;
    }

    /**
     * One open decision per lesson: a lesson this change cancels must not be the subject of another
     * open change. A block may still cover lessons whose other decision only cancels them.
     */
    private void requireNoCompetingDecision(Change candidate, ChangeEffect effect) {
        Set<UUID> concerned = new HashSet<>(effect.cancelledIds());
        if (candidate.getOriginalLessonId() != null) {
            concerned.add(candidate.getOriginalLessonId());
        }
        if (candidate.getSwapLessonId() != null) {
            concerned.add(candidate.getSwapLessonId());
        }
        if (concerned.isEmpty()) {
            return;
        }
        Stream.concat(changes.findAllByOriginalLessonIdIn(concerned).stream(),
                        changes.findAllBySwapLessonIdIn(concerned).stream())
                .filter(Change::isOpen)
                .filter(other -> !other.getId().equals(candidate.getId()))
                .filter(other -> candidate.getKind() != ChangeKind.BLOCK || createsLessons(other))
                .findFirst()
                .ifPresent(other -> {
                    throw new ConflictException("Для цього заняття вже є рішення: %s («%s»). Скасуйте його, щоб обрати інше."
                            .formatted(kindLabel(other.getKind()), ChangeStatusMachine.label(other.getStatus())));
                });
    }

    private static boolean createsLessons(Change change) {
        return change.getKind() != ChangeKind.CANCEL
                && !(change.getKind() == ChangeKind.DISCIPLINE_FINISHED && change.getNewDisciplineId() == null);
    }

    static String kindLabel(ChangeKind kind) {
        return switch (kind) {
            case REPLACE_TEACHER -> "заміна викладача";
            case REPLACE_DISCIPLINE -> "заміна дисципліни";
            case SWAP -> "перестановка пар";
            case CANCEL -> "пари не буде";
            case ADDITIONAL -> "додаткова пара";
            case ROOM_CHANGE -> "заміна аудиторії";
            case BLOCK -> "блок";
            case DISCIPLINE_FINISHED -> "вичитано";
            case MERGE_SUBGROUPS -> "об'єднання підгруп";
        };
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
