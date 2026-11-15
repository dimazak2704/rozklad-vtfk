package ua.vtc.rozklad.core.calendar.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import ua.vtc.rozklad.core.calendar.CalendarEdited;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodKindRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.internal.CalendarChangePublisher.Affected;
import ua.vtc.rozklad.core.calendar.internal.CalendarChangePublisher.PeriodSnapshot;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.LessonCounts;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.PeriodKindDto;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.PeriodKindImpactDto;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.PeriodKindRequest;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * Довідник «Види періодів» ({@code /api/admin/period-kinds}): the kinds are data, their effect is one of
 * {@link PeriodEffect}. A new effect of a kind that has periods is a calendar edit of all of them: the same
 * re-materialization and the same 409 for live changes as an edit of a period, so it can be tried first with a
 * dry run that rolls back. A new name or colour only tells the site and bot caches; other fields publish
 * nothing.
 */
@Service
@Transactional(readOnly = true)
public class PeriodKindService {

    static final String BUILTIN_CODE = "Код вбудованого виду не змінюється.";
    static final String CODE_TAKEN = "Код «%s» уже має вид «%s».";
    static final String BUILTIN_DELETE = "Вбудований вид не видаляють: його можна вимкнути.";
    static final String ONLINE_SWITCH = "Ефект «онлайн» можна ввімкнути чи вимкнути лише для виду без періодів: "
            + "спершу видаліть періоди цього виду.";
    static final String ROOMS_OR_SUPERVISORS = "Періоди цього виду мають аудиторію або керівників: спершу приберіть їх.";

    private static final int POSITION_STEP = 10;
    private static final int MAX_POSITION = 9999;

    private final PeriodKindRepository kinds;
    private final CalendarPeriodRepository periods;
    private final SemesterRepository semesters;
    private final CatalogQueries catalog;
    private final CalendarChangePublisher changes;

    PeriodKindService(PeriodKindRepository kinds, CalendarPeriodRepository periods, SemesterRepository semesters,
                      CatalogQueries catalog, CalendarChangePublisher changes) {
        this.kinds = kinds;
        this.periods = periods;
        this.semesters = semesters;
        this.catalog = catalog;
        this.changes = changes;
    }

    /** By position, then code; {@code active} filters when given. */
    public List<PeriodKindDto> list(Boolean active) {
        Map<UUID, Long> usage = usage();
        return kinds.findAllByOrderByPositionAscCodeAsc().stream()
                .filter(kind -> active == null || kind.isActive() == active)
                .map(kind -> PeriodKindDto.of(kind, usage.getOrDefault(kind.getId(), 0L)))
                .toList();
    }

    public PeriodKindDto get(UUID id) {
        PeriodKind kind = find(id);
        return PeriodKindDto.of(kind, periods.countByKindId(id));
    }

    @Transactional
    public PeriodKindDto create(PeriodKindRequest request) {
        List<String> aliases = aliases(request);
        checkClashes(null, request.code().strip(), aliases);
        PeriodKind kind = new PeriodKind(request.code().strip(), request.name().strip(), request.effect());
        copyDetails(request, aliases, kind);
        kind.setActive(request.active() == null || request.active());
        kind.setPosition(request.position() != null ? request.position()
                : Math.min(MAX_POSITION, kinds.maxPosition() + POSITION_STEP));
        kinds.saveAndFlush(kind);
        return PeriodKindDto.of(kind, 0);
    }

    /** Saves the edit; 409 when the effect change is blocked or clashes with live changes. */
    @Transactional
    public PeriodKindDto update(UUID id, PeriodKindRequest request) {
        return edit(id, request, false).kind();
    }

    /** The same edit in a transaction that is rolled back: nothing is saved and no event leaves. */
    @Transactional
    public PeriodKindImpactDto preview(UUID id, PeriodKindRequest request) {
        try {
            return edit(id, request, true);
        } finally {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    @Transactional
    public void delete(UUID id) {
        PeriodKind kind = find(id);
        if (kind.isBuiltin()) {
            throw new ConflictException(BUILTIN_DELETE);
        }
        long used = periods.countByKindId(id);
        if (used > 0) {
            throw new ConflictException("Вид «%s» %s %d %s календаря: спершу видаліть їх або змініть їм вид."
                    .formatted(kind.getCode(), UkrainianText.plural(used, "має", "мають", "мають"), used,
                            UkrainianText.plural(used, "період", "періоди", "періодів")));
        }
        kinds.delete(kind);
        kinds.flush();
    }

    private PeriodKindImpactDto edit(UUID id, PeriodKindRequest request, boolean dryRun) {
        PeriodKind kind = find(id);
        checkVersion(kind, request.version());
        String code = request.code().strip();
        if (kind.isBuiltin() && !kind.getCode().equals(code)) {
            throw FieldValidationException.of("code", BUILTIN_CODE);
        }
        List<String> aliases = aliases(request);
        checkClashes(kind, code, aliases);

        PeriodEffect oldEffect = kind.getEffect();
        boolean effectChanged = request.effect() != oldEffect;
        boolean textChanged = !kind.getName().equals(request.name().strip())
                || !Objects.equals(kind.getColor(), upper(request.color()));
        boolean loaded = effectChanged || textChanged || dryRun;
        List<CalendarPeriod> ofKind = loaded ? periods.findAllByKind(id) : List.of();
        List<PeriodSnapshot> snapshots = ofKind.stream().map(PeriodSnapshot::of).toList();
        Range range = range(ofKind);
        List<String> blockers = effectChanged ? blockers(ofKind, oldEffect, request.effect()) : List.of();
        if (!dryRun && !blockers.isEmpty()) {
            throw new ConflictException(blockers.getFirst());
        }
        boolean recheck = effectChanged && !ofKind.isEmpty() && blockers.isEmpty();
        // календар «до» читаємо раніше, ніж правка торкнеться сутностей: запит спершу скинув би її в базу
        StudyCalendar before = recheck ? changes.before(range.from(), range.to()) : null;

        kind.setCode(code);
        kind.setName(request.name().strip());
        kind.setEffect(request.effect());
        copyDetails(request, aliases, kind);
        if (request.active() != null) {
            kind.setActive(request.active());
        }
        if (request.position() != null) {
            kind.setPosition(request.position());
        }
        kinds.flush();

        CalendarEdited.Outcome outcome = new CalendarEdited.Outcome();
        List<ConflictDto> conflicts = List.of();
        if (recheck) {
            ofKind.forEach(CalendarPeriod::syncLegacyKind);
            periods.flush();
            try {
                // числа заповнено ще до відмови: пробний прогін показує їх і для правки, яку буде відхилено
                changes.periodsChanged(snapshots, range.from(), range.to(), before, outcome);
            } catch (ConflictException clash) {
                if (!dryRun) {
                    throw clash;
                }
                conflicts = clash.getConflicts();
            }
        } else if (textChanged && !ofKind.isEmpty() && !effectChanged) {
            changes.textsChanged(snapshots, range.from(), range.to());
        }

        PeriodKindDto dto = PeriodKindDto.of(kind, loaded ? ofKind.size() : periods.countByKindId(id));
        // дати й групи потрібні лише пробному прогону: збережена правка відповідає самим видом
        Affected affected = ofKind.isEmpty() || !dryRun ? null
                : changes.affected(snapshots, range.from(), range.to());
        List<LocalDate> dates = affected == null ? List.of() : List.copyOf(affected.dates());
        List<GroupRef> groups = affected == null ? List.of() : catalog.groupRefs(affected.groupIds()).values()
                .stream()
                .sorted(Comparator.comparing(GroupRef::code, UkrainianOrder.NATURAL))
                .toList();
        return new PeriodKindImpactDto(dto, effectChanged, ofKind.size(), dates, groups,
                new LessonCounts(outcome.added(), outcome.removed(), outcome.updated()), conflicts, blockers,
                blockers.isEmpty() && conflicts.isEmpty());
    }

    /** Why an effect change of a kind with periods is refused (empty = allowed). */
    private static List<String> blockers(List<CalendarPeriod> ofKind, PeriodEffect from, PeriodEffect to) {
        if (ofKind.isEmpty()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        if (from == PeriodEffect.ONLINE || to == PeriodEffect.ONLINE) {
            // режим навчання потрібен лише онлайну: періоди стали б неповними або зайвими
            reasons.add(ONLINE_SWITCH);
        }
        if (to != PeriodEffect.SUSPEND && ofKind.stream()
                .anyMatch(period -> period.getRoomId() != null || !period.getSupervisorIds().isEmpty())) {
            reasons.add(ROOMS_OR_SUPERVISORS);
        }
        return reasons;
    }

    /** From the earliest start to the latest end of the semesters of the periods. */
    private Range range(List<CalendarPeriod> ofKind) {
        if (ofKind.isEmpty()) {
            return new Range(null, null);
        }
        List<Semester> found = semesters.findAllById(ofKind.stream().map(CalendarPeriod::getSemesterId)
                .distinct().toList());
        LocalDate from = found.stream().map(Semester::getStartsOn).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = found.stream().map(Semester::getEndsOn).max(Comparator.naturalOrder()).orElseThrow();
        return new Range(from, to);
    }

    /** A code or alias may not equal (case ignored) the code or an alias of another kind. */
    private void checkClashes(PeriodKind self, String code, List<String> aliases) {
        Map<String, String> taken = new HashMap<>();
        for (PeriodKind other : kinds.findAll()) {
            if (self != null && other.getId().equals(self.getId())) {
                continue;
            }
            taken.put(key(other.getCode()), other.getName());
            other.getAliases().forEach(alias -> taken.put(key(alias), other.getName()));
        }
        List<String> tokens = new ArrayList<>();
        tokens.add(code);
        tokens.addAll(aliases);
        for (String token : tokens) {
            String owner = taken.get(key(token));
            if (owner != null) {
                throw new ConflictException(CODE_TAKEN.formatted(token, owner));
            }
        }
    }

    /** Aliases stripped, each once (case ignored), without the kind's own code. */
    private static List<String> aliases(PeriodKindRequest request) {
        if (request.aliases() == null) {
            return List.of();
        }
        String code = key(request.code());
        Map<String, String> unique = new LinkedHashMap<>();
        request.aliases().stream().map(String::strip)
                .filter(alias -> !key(alias).equals(code))
                .forEach(alias -> unique.putIfAbsent(key(alias), alias));
        return List.copyOf(unique.values());
    }

    private static void copyDetails(PeriodKindRequest request, List<String> aliases, PeriodKind kind) {
        kind.setColor(upper(request.color()));
        kind.setAliases(aliases);
        kind.setNoteTemplate(request.noteTemplate() == null || request.noteTemplate().isBlank() ? null
                : request.noteTemplate().strip());
    }

    private static String upper(String color) {
        return color == null || color.isBlank() ? null : color.strip().toUpperCase(Locale.ROOT);
    }

    private static String key(String token) {
        return token.strip().toLowerCase(Locale.ROOT);
    }

    private PeriodKind find(UUID id) {
        return kinds.findById(id).orElseThrow(() -> NotFoundException.of("Вид періоду", id));
    }

    private Map<UUID, Long> usage() {
        Map<UUID, Long> usage = new HashMap<>();
        for (Object[] row : periods.countByKind()) {
            usage.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return usage;
    }

    private record Range(LocalDate from, LocalDate to) {
    }
}
