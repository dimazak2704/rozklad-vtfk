package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.core.calendar.BellSlot;
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
     * {@code substitution.confirmation-requested} to the assigned teacher. A reminder repeats the
     * event with the same change id and the original (already passed) deadline.
     */
    public void confirmationRequested(Change change) {
        Teacher teacher = catalog.teacher(change.getNewTeacherId());
        UUID disciplineId = change.getNewDisciplineId() != null ? change.getNewDisciplineId()
                : change.getOrigDisciplineId();
        Discipline discipline = disciplineId == null ? null : catalog.discipline(disciplineId);
        UUID roomId = change.getNewRoomId() != null ? change.getNewRoomId() : change.getOrigRoomId();
        Room room = roomId == null ? null : catalog.room(roomId);
        Teacher original = change.getOrigTeacherId() == null ? null : catalog.teacher(change.getOrigTeacherId());
        Map<Integer, BellSlot> times = calendar.findSemester(change.getDate())
                .map(semester -> calendar.pairTimes(semester, change.getDate()))
                .orElse(Map.of());
        BellSlot first = times.get(change.getPairFrom());
        BellSlot last = times.get(change.getPairTo());
        events.publishEvent(new SubstitutionConfirmationRequested(EventEnvelope.create(clock), change.getId(),
                teacher.getId(), change.getDate(), change.getPairFrom(),
                first == null ? null : first.getStartsAt(), last == null ? null : last.getEndsAt(),
                catalog.group(change.getGroupId()).getCode(), change.getSubgroupNumber(),
                discipline == null ? null : discipline.getName(),
                original == null ? null : original.getShortName(),
                room == null ? null : room.getName(), change.getConfirmDeadline()));
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
