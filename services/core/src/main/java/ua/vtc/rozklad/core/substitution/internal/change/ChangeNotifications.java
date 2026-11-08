package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.core.substitution.ApprovalMode;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;

/**
 * Integration events (transactional outbox) and SSE pushes of the change workflow. Events carry
 * no absence reasons or comments (DOMAIN §7); SSE goes to dispatchers and to the assigned teacher
 * after the transaction commits.
 */
@Component
public class ChangeNotifications {

    private final ApplicationEventPublisher events;
    private final SseAfterCommit sse;
    private final ChangeViews views;
    private final CatalogLookup catalog;
    private final SubstitutionCalendar calendar;
    private final Clock clock;

    public ChangeNotifications(ApplicationEventPublisher events, SseAfterCommit sse, ChangeViews views,
                               CatalogLookup catalog, SubstitutionCalendar calendar, Clock clock) {
        this.events = events;
        this.sse = sse;
        this.views = views;
        this.catalog = catalog;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * {@code substitution.confirmation-requested} to the assigned teacher: a request (CONFIRM) or a
     * notice (NOTIFY, no deadline). {@code disciplineName} is what the teacher will teach (the new
     * discipline of a REPLACE_DISCIPLINE change), {@code originalDisciplineName} the lesson's own one.
     * A reminder repeats the event with the same change id and the original (already passed) deadline.
     */
    public void confirmationRequested(Change change) {
        Teacher teacher = catalog.teacher(change.getNewTeacherId());
        UUID disciplineId = change.getNewDisciplineId() != null ? change.getNewDisciplineId()
                : change.getOrigDisciplineId();
        Discipline discipline = disciplineId == null ? null : catalog.discipline(disciplineId);
        Discipline originalDiscipline = change.getOrigDisciplineId() == null ? null
                : catalog.discipline(change.getOrigDisciplineId());
        UUID roomId = change.getNewRoomId() != null ? change.getNewRoomId() : change.getOrigRoomId();
        Room room = roomId == null ? null : catalog.room(roomId);
        Teacher original = change.getOrigTeacherId() == null ? null : catalog.teacher(change.getOrigTeacherId());
        Map<Integer, PairTime> times = calendar.pairTimes(change.getDate());
        PairTime first = times.get(change.getPairFrom());
        PairTime last = times.get(change.getPairTo());
        SubstitutionRequestMode mode = change.getApprovalMode() == ApprovalMode.NOTIFY
                ? SubstitutionRequestMode.NOTIFY : SubstitutionRequestMode.CONFIRM;
        events.publishEvent(new SubstitutionConfirmationRequested(EventEnvelope.create(clock), change.getId(),
                teacher.getId(), change.getDate(), change.getPairFrom(),
                first == null ? null : first.startsAt(), last == null ? null : last.endsAt(),
                // після об'єднання підгруп викладач веде всю групу
                catalog.group(change.getGroupId()).getCode(),
                change.getKind() == ChangeKind.MERGE_SUBGROUPS ? 0 : change.getSubgroupNumber(),
                discipline == null ? null : discipline.getName(),
                original == null ? null : original.getShortName(),
                room == null ? null : room.getName(), change.getConfirmDeadline(), mode, change.getKind(),
                originalDiscipline == null ? null : originalDiscipline.getName(),
                change.getReasonCode() == null ? null : change.getReasonCode().name()));
    }

    /** {@code substitution.teacher-replied}: a NOTIFY answer that kept the status (DOMAIN §7). */
    public void teacherReplied(Change change) {
        events.publishEvent(new SubstitutionTeacherReplied(EventEnvelope.create(clock), change.getId(),
                change.getNewTeacherId(), change.getTeacherAnswer(), change.getStatus(), change.getDate(),
                change.getPairFrom(), catalog.group(change.getGroupId()).getCode()));
    }

    /** {@code substitution.status-changed} (the assigned teacher may be {@code null}). */
    public void statusChanged(Change change) {
        events.publishEvent(new SubstitutionStatusChanged(EventEnvelope.create(clock), change.getId(),
                change.getStatus(), change.getNewTeacherId(), change.getDate(),
                catalog.group(change.getGroupId()).getCode()));
    }

    /** SSE {@code change.updated} with the fresh DTO for dispatchers and the assigned teacher. */
    public void pushUpdate(Change change) {
        ChangeDto dto = views.toDto(change);
        sse.sendToRole(Roles.ADMIN, SseEventTypes.CHANGE_UPDATED, dto);
        if (change.getNewTeacherId() != null) {
            UUID account = catalog.teacher(change.getNewTeacherId()).getKeycloakUserId();
            sse.sendToUser(account, SseEventTypes.CHANGE_UPDATED, dto);
        }
    }
}
