package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.substitution.TeacherPreferenceRepository;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/**
 * {@code GET /api/admin/lessons/{id}/teacher-availability?q=}: every active teacher with his state
 * at the lesson's pair, for the manual «Інший викладач…» path (REPLACE_TEACHER is an exception and
 * never recommended). Unranked: free teachers first, then alphabetical. A fixed number of queries.
 */
@Service
@Transactional(readOnly = true)
public class TeacherAvailability {

    private static final Set<ChangeStatus> OPEN = EnumSet.of(ChangeStatus.DRAFT, ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final LessonRepository lessons;
    private final TeacherRepository teachers;
    private final ChangeRepository changes;
    private final AbsenceRepository absences;
    private final TeacherPreferenceRepository preferences;
    private final OptionsDeclines declines;
    private final CatalogLookup catalog;
    private final Clock clock;

    public TeacherAvailability(LessonRepository lessons, TeacherRepository teachers, ChangeRepository changes,
                               AbsenceRepository absences, TeacherPreferenceRepository preferences,
                               OptionsDeclines declines, CatalogLookup catalog, Clock clock) {
        this.lessons = lessons;
        this.teachers = teachers;
        this.changes = changes;
        this.absences = absences;
        this.preferences = preferences;
        this.declines = declines;
        this.catalog = catalog;
        this.clock = clock;
    }

    public enum Status {
        FREE,
        BUSY,
        ABSENT,
        UNAVAILABLE,
        DECLINED
    }

    /** {@code detail}: «3 пара, 4КІ2» for BUSY, «14:05» for DECLINED, otherwise {@code null}. */
    public record Item(TeacherRef teacher, Status status, String detail) {
    }

    public List<Item> of(UUID lessonId, String query) {
        Lesson lesson = lessons.findById(lessonId).orElseThrow(() -> NotFoundException.of("Заняття", lessonId));
        int pair = lesson.getPairNumber();
        List<Lesson> atPair = lessons.findAllByDateAndStatus(lesson.getDate(), LessonStatus.SCHEDULED).stream()
                .filter(other -> other.getPairNumber() == pair && other.getTeacherId() != null)
                .toList();
        List<Change> open = changes.findAllByDateAndStatusIn(lesson.getDate(), OPEN);
        Set<UUID> freed = new HashSet<>();
        open.stream().filter(change -> change.getKind() != ChangeKind.ROOM_CHANGE)
                .flatMap(change -> Stream.of(change.getOriginalLessonId(), change.getSwapLessonId()))
                .filter(Objects::nonNull)
                .forEach(freed::add);
        Map<UUID, UUID> busyIn = new HashMap<>();
        atPair.stream().filter(other -> !freed.contains(other.getId()) && !other.getId().equals(lesson.getId()))
                .forEach(other -> busyIn.putIfAbsent(other.getTeacherId(), other.getGroupId()));
        open.stream().filter(change -> change.getNewTeacherId() != null && change.coversPair(pair))
                .forEach(change -> busyIn.putIfAbsent(change.getNewTeacherId(), change.getGroupId()));
        Set<UUID> absent = new HashSet<>();
        absences.findActiveOverlapping(lesson.getDate(), lesson.getDate(), null).stream()
                .filter(absence -> absence.covers(lesson.getDate(), pair))
                .map(Absence::getTeacherId)
                .forEach(absent::add);
        Set<UUID> unavailable = new HashSet<>();
        preferences.findAllBySemesterIdAndWeekdayAndPairNumber(lesson.getSemesterId(),
                        lesson.getDate().getDayOfWeek().getValue(), pair).stream()
                .filter(preference -> preference.getKind() == PreferenceKind.UNAVAILABLE)
                .map(TeacherPreference::getTeacherId)
                .forEach(unavailable::add);
        Map<UUID, Instant> declined = new HashMap<>();
        declines.forLesson(lesson.getId()).forEach(decline -> declined.merge(decline.teacherId(), decline.at(),
                (first, second) -> first.isAfter(second) ? first : second));
        Map<UUID, StudentGroup> groups = catalog.groups(busyIn.values());

        String needle = normalize(query);
        Comparator<String> alphabetical = UkrainianText.collator();
        return teachers.findAllByActiveTrueOrderByShortName().stream()
                .filter(teacher -> !teacher.getId().equals(lesson.getTeacherId()))
                .filter(teacher -> needle.isEmpty() || matches(teacher, needle))
                .map(teacher -> {
                    UUID id = teacher.getId();
                    if (absent.contains(id)) {
                        return new Item(TeacherRef.of(teacher), Status.ABSENT, null);
                    }
                    if (declined.containsKey(id)) {
                        return new Item(TeacherRef.of(teacher), Status.DECLINED,
                                TIME.format(declined.get(id).atZone(clock.getZone())));
                    }
                    if (busyIn.containsKey(id)) {
                        StudentGroup group = groups.get(busyIn.get(id));
                        return new Item(TeacherRef.of(teacher), Status.BUSY,
                                pair + " пара, " + (group == null ? "?" : group.getCode()));
                    }
                    if (unavailable.contains(id)) {
                        return new Item(TeacherRef.of(teacher), Status.UNAVAILABLE, null);
                    }
                    return new Item(TeacherRef.of(teacher), Status.FREE, null);
                })
                .sorted(Comparator.comparing((Item item) -> item.status() != Status.FREE)
                        .thenComparing(item -> item.teacher().shortName(), alphabetical))
                .toList();
    }

    private static boolean matches(Teacher teacher, String needle) {
        return Stream.of(teacher.getShortName(), teacher.getDisplayName(), teacher.getLastName())
                .filter(Objects::nonNull)
                .anyMatch(name -> normalize(name).contains(needle));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.forLanguageTag("uk")).replace('’', '\'')
                .replace('ʼ', '\'');
    }
}
