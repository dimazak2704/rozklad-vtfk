package ua.vtc.rozklad.core.substitution.internal.preference;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.substitution.TeacherPreferenceRepository;

/**
 * Unwanted / unavailable slots of a teacher in a semester (DOMAIN §5): UNAVAILABLE excludes the
 * teacher from substitutions in that slot, UNWANTED lowers the candidate score. PUT replaces the
 * whole grid.
 */
@Service
@Transactional
public class PreferenceService {

    private static final Comparator<PreferenceDto> GRID_ORDER =
            Comparator.comparing(PreferenceDto::weekday).thenComparing(PreferenceDto::pair);

    private final TeacherPreferenceRepository preferences;
    private final SemesterRepository semesters;
    private final Clock clock;

    public PreferenceService(TeacherPreferenceRepository preferences, SemesterRepository semesters, Clock clock) {
        this.preferences = preferences;
        this.semesters = semesters;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PreferenceDto> grid(UUID teacherId, UUID semesterId) {
        Semester semester = semester(semesterId);
        return preferences.findAllByTeacherIdAndSemesterId(teacherId, semester.getId()).stream()
                .map(preference -> new PreferenceDto(preference.getWeekday(), preference.getPairNumber(),
                        preference.getKind(), preference.getNote()))
                .sorted(GRID_ORDER)
                .toList();
    }

    public List<PreferenceDto> replace(UUID teacherId, UUID semesterId, List<PreferenceDto> grid) {
        Semester semester = semester(semesterId);
        Set<String> slots = new HashSet<>();
        for (PreferenceDto slot : grid) {
            if (!slots.add(slot.weekday() + "/" + slot.pair())) {
                throw new BusinessRuleException("PREFERENCE_DUPLICATE", "Слот «%s, %d пара» вказано двічі."
                        .formatted(UkrainianText.weekday(DayOfWeek.of(slot.weekday())), slot.pair()));
            }
        }
        preferences.deleteAllOf(teacherId, semester.getId());
        preferences.saveAll(grid.stream()
                .map(slot -> new TeacherPreference(teacherId, semester.getId(), slot.weekday(), slot.pair(),
                        slot.kind(), slot.note() == null || slot.note().isBlank() ? null : slot.note().strip()))
                .toList());
        return grid(teacherId, semester.getId());
    }

    /** The requested semester, or the active one, or the one containing today. */
    private Semester semester(UUID semesterId) {
        if (semesterId != null) {
            return semesters.findById(semesterId).orElseThrow(() -> NotFoundException.of("Семестр", semesterId));
        }
        return semesters.findByActiveTrue()
                .or(() -> semesters.findContaining(LocalDate.now(clock)))
                .orElseThrow(() -> new BusinessRuleException("NO_ACTIVE_SEMESTER",
                        "Не знайдено активного семестру. Вкажіть семестр явно."));
    }
}
