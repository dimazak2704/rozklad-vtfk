package ua.vtc.rozklad.core.substitution.internal.absence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceRequests.AbsenceData;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;

/**
 * Teacher absences (DOMAIN §7) reported by the dispatcher, by the teacher in the cabinet or via the
 * Telegram bot. A new absence publishes {@code absence.reported} (without the private reason) and
 * notifies dispatchers; cancelling or shrinking an absence cancels the changes made for the lessons
 * it no longer covers. Overlapping absences of one teacher are rejected; writes of one teacher are
 * serialized by {@link AbsenceRepository#lockTeacher}, so parallel requests cannot both pass the check.
 */
@Service
@Transactional
public class AbsenceService {

    /** SSE event after an absence was edited or cancelled (additive to CONTRACTS, data: {@link AbsenceDto.Summary}). */
    public static final String ABSENCE_UPDATED = "absence.updated";

    private final AbsenceRepository absences;
    private final CatalogLookup catalog;
    private final ChangeCommands changeCommands;
    private final ApplicationEventPublisher events;
    private final SseAfterCommit sse;
    private final Clock clock;

    public AbsenceService(AbsenceRepository absences, CatalogLookup catalog, ChangeCommands changeCommands,
                          ApplicationEventPublisher events, SseAfterCommit sse, Clock clock) {
        this.absences = absences;
        this.catalog = catalog;
        this.changeCommands = changeCommands;
        this.events = events;
        this.sse = sse;
        this.clock = clock;
    }

    /** Active absences overlapping {@code [from, to]}, optionally of one teacher. */
    @Transactional(readOnly = true)
    public List<AbsenceDto> list(LocalDate from, LocalDate to, UUID teacherId) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("INVALID_PERIOD", "Кінець періоду не може бути раніше за початок.");
        }
        return toDtos(absences.findActiveOverlapping(from, to, teacherId));
    }

    /** All absences of a teacher (including cancelled), newest first. */
    @Transactional(readOnly = true)
    public List<AbsenceDto> ofTeacher(UUID teacherId) {
        return toDtos(absences.findAllByTeacherIdOrderByDateFromDesc(teacherId));
    }

    @Transactional(readOnly = true)
    public AbsenceDto get(UUID id) {
        return toDtos(List.of(find(id))).getFirst();
    }

    public AbsenceDto report(UUID teacherId, AbsenceData data, AbsenceSource source, String actor) {
        Teacher teacher = catalog.teacher(teacherId, "teacherId");
        LocalDate today = LocalDate.now(clock);
        // викладач (кабінет, бот) повідомляє лише про сьогодні й майбутнє: минуле — справа диспетчера,
        // інакше відсутність лягла б на вже опубліковані розпорядження і її не можна було б скасувати
        if (source != AbsenceSource.ADMIN && data.dateTo().isBefore(today)) {
            throw new BusinessRuleException("ABSENCE_IN_PAST", "Не можна повідомити про відсутність, яка вже минула.");
        }
        if (source != AbsenceSource.ADMIN && data.dateFrom().isBefore(today)) {
            throw new BusinessRuleException("ABSENCE_IN_PAST", ("Відсутність можна повідомити лише з сьогодні (%s). "
                    + "Про минулі дні повідомте диспетчера.").formatted(today.format(SubstitutionCalendar.DATE)));
        }
        Absence absence = new Absence(teacher.getId(), data.dateFrom(), data.dateTo(), data.reasonCategory(), source,
                actor);
        absence.setPairs(data.pairs());
        absence.setComment(data.cleanComment());
        absences.lockTeacher(teacher.getId());
        requireNoOverlap(absence);
        absences.save(absence);
        events.publishEvent(new AbsenceReported(EventEnvelope.create(clock), absence.getId(), teacher.getId(),
                teacher.getShortName(), absence.getDateFrom(), absence.getDateTo(),
                absence.isWholeDay() ? null : absence.getPairs(), source));
        absences.flush();
        AbsenceDto dto = toDto(absence, teacher);
        sse.sendToRole(Roles.ADMIN, SseEventTypes.ABSENCE_CREATED, AbsenceDto.Summary.of(dto));
        return dto;
    }

    /** {@code version} is required: without it a stale form would silently overwrite a newer edit. */
    public AbsenceDto update(UUID id, UUID teacherId, AbsenceData data, Long version, String actor) {
        if (version == null) {
            throw FieldValidationException.of("version", "Вкажіть версію відсутності (поле version з відповіді).");
        }
        Absence absence = find(id);
        if (version != absence.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Absence.class, id);
        }
        if (!absence.getTeacherId().equals(teacherId)) {
            throw new BusinessRuleException("ABSENCE_TEACHER_FIXED",
                    "Викладача відсутності змінити не можна: скасуйте її та створіть нову.");
        }
        if (!absence.isActive()) {
            throw new BusinessRuleException("ABSENCE_CANCELLED", "Скасовану відсутність змінити не можна.");
        }
        absence.setDates(data.dateFrom(), data.dateTo());
        absence.setPairs(data.pairs());
        absence.setReasonCategory(data.reasonCategory());
        absence.setComment(data.cleanComment());
        absences.lockTeacher(absence.getTeacherId());
        requireNoOverlap(absence);
        changeCommands.cancelForAbsence(absence.getId(), change -> !stillCovered(absence, change), actor,
                "Пара більше не входить у відсутність");
        return announceUpdate(absence);
    }

    /** DELETE by the dispatcher: the absence and its not yet applied changes are cancelled. */
    public void cancel(UUID id, String actor) {
        Absence absence = find(id);
        if (!absence.isActive()) {
            return;
        }
        absence.cancel();
        changeCommands.cancelForAbsence(absence.getId(), change -> true, actor, "Відсутність скасовано");
        announceUpdate(absence);
    }

    /** DELETE by the teacher: only own absences that have not started yet. */
    public void cancelOwn(UUID id, UUID teacherId, String actor) {
        Absence absence = find(id);
        if (!absence.getTeacherId().equals(teacherId)) {
            throw new AccessDeniedException("Це відсутність іншого викладача");
        }
        if (!absence.getDateFrom().isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("ABSENCE_STARTED",
                    "Скасувати можна лише майбутню відсутність. Якщо плани змінилися, повідомте диспетчера.");
        }
        cancel(id, actor);
    }

    private AbsenceDto announceUpdate(Absence absence) {
        absences.flush();
        AbsenceDto dto = toDto(absence, catalog.teacher(absence.getTeacherId()));
        sse.sendToRole(Roles.ADMIN, ABSENCE_UPDATED, AbsenceDto.Summary.of(dto));
        return dto;
    }

    private Absence find(UUID id) {
        return absences.findById(id).orElseThrow(() -> NotFoundException.of("Відсутність", id));
    }

    /** One teacher cannot be absent twice at the same pair. */
    private void requireNoOverlap(Absence candidate) {
        absences.findActiveOverlapping(candidate.getDateFrom(), candidate.getDateTo(), candidate.getTeacherId())
                .stream()
                .filter(other -> !other.getId().equals(candidate.getId()))
                .filter(other -> other.isWholeDay() || candidate.isWholeDay()
                        || !Collections.disjoint(other.getPairs(), candidate.getPairs()))
                .findFirst()
                .ifPresent(other -> {
                    throw new ConflictException("Відсутність перетинається з уже зареєстрованою (%s–%s)."
                            .formatted(other.getDateFrom().format(SubstitutionCalendar.DATE),
                                    other.getDateTo().format(SubstitutionCalendar.DATE)));
                });
    }

    /** A change for a lesson is still needed while the absence covers that lesson's pair. */
    private static boolean stillCovered(Absence absence, Change change) {
        return change.getOriginalLessonId() == null || absence.covers(change.getDate(), change.getPairFrom());
    }

    private List<AbsenceDto> toDtos(List<Absence> found) {
        Map<UUID, Teacher> teachers = catalog.teachers(found.stream().map(Absence::getTeacherId).toList());
        return found.stream().map(absence -> toDto(absence, teachers.get(absence.getTeacherId()))).toList();
    }

    private static AbsenceDto toDto(Absence absence, Teacher teacher) {
        return new AbsenceDto(absence.getId(), CatalogLookup.ref(teacher), absence.getDateFrom(), absence.getDateTo(),
                absence.isWholeDay() ? null : absence.getPairs(), absence.getReasonCategory(), absence.getComment(),
                absence.getSource(), absence.getStatus(), absence.getCreatedBy(), absence.getCreatedAt(),
                absence.getVersion());
    }
}
