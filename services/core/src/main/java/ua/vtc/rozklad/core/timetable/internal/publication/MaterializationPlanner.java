package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.DayInfo;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.shared.domain.Ids;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.GroupFact;
import ua.vtc.rozklad.core.timetable.internal.conflict.RoomFact;

/**
 * Materialization of DOMAIN.md §6: for every study date of {@code [from, to]} take the template
 * lessons of that weekday with parity BOTH or the parity of the date, skip groups whose day is
 * closed (holiday, practice), mark lessons online in a study-mode period. A cohort slot governed
 * by a kept lesson (an applied change) is left to that change; a template lesson whose teacher or
 * exclusive room is taken by a kept lesson is not created and reported as a hard conflict.
 */
public final class MaterializationPlanner {

    private static final Comparator<TemplateLesson> ORDER = Comparator.comparingInt(TemplateLesson::getPairNumber)
            .thenComparing(TemplateLesson::getGroupId)
            .thenComparingInt(TemplateLesson::getSubgroupNumber)
            .thenComparing(TemplateLesson::getId);

    public Plan plan(Input input) {
        Map<Integer, List<TemplateLesson>> byWeekday = new HashMap<>();
        input.template().stream().sorted(ORDER)
                .forEach(lesson -> byWeekday.computeIfAbsent(lesson.getWeekday(), day -> new ArrayList<>()).add(lesson));
        Map<SlotKey, List<KeptLesson>> keptBySlot = new HashMap<>();
        input.kept().forEach(lesson -> keptBySlot
                .computeIfAbsent(new SlotKey(lesson.date(), lesson.pair()), key -> new ArrayList<>()).add(lesson));

        List<LocalDate> dates = input.calendar().studyDates(input.from(), input.to());
        List<NewLesson> created = new ArrayList<>();
        List<ConflictDto> clashes = new ArrayList<>();
        int covered = 0;
        Map<GroupDay, DayInfo> groupDays = new HashMap<>();
        for (LocalDate date : dates) {
            Parity parity = input.calendar().day(date, null).parity();
            for (TemplateLesson lesson : byWeekday.getOrDefault(date.getDayOfWeek().getValue(), List.of())) {
                if (lesson.getParity() != Parity.BOTH && lesson.getParity() != parity) {
                    continue;
                }
                GroupFact group = input.facts().group(lesson.getGroupId());
                DayInfo groupDay = groupDays.computeIfAbsent(new GroupDay(lesson.getGroupId(), date),
                        key -> input.calendar().day(date, group == null ? null
                                : new GroupScope(group.id(), group.course())));
                if (!groupDay.studyDay()) {
                    continue;
                }
                List<KeptLesson> keptHere = keptBySlot.getOrDefault(new SlotKey(date, lesson.getPairNumber()), List.of());
                if (keptHere.stream().anyMatch(kept -> kept.coversCohort(lesson.getGroupId(),
                        lesson.getSubgroupNumber()))) {
                    covered++;
                    continue;
                }
                Optional<ConflictDto> clash = clash(lesson, date, keptHere, input.facts());
                if (clash.isPresent()) {
                    clashes.add(clash.get());
                    continue;
                }
                created.add(newLesson(input, lesson, date, groupDay.online()));
            }
        }
        return new Plan(created, covered, clashes, dates);
    }

    private NewLesson newLesson(Input input, TemplateLesson lesson, LocalDate date, boolean onlineDay) {
        RoomFact room = input.facts().room(lesson.getRoomId());
        return new NewLesson(Ids.newId(), input.semesterId(), input.versionId(), lesson.getId(), date,
                lesson.getPairNumber(), lesson.getGroupId(), lesson.getSubgroupNumber(), lesson.getDisciplineId(),
                lesson.getTeacherId(), lesson.getRoomId(), room == null || room.maxParallel() == 1,
                lesson.getStreamId(), lesson.getLessonKind(), lesson.isOnline() || onlineDay);
    }

    /** The teacher or the exclusive room is busy with a kept lesson (the DB would reject both lessons). */
    private static Optional<ConflictDto> clash(TemplateLesson lesson, LocalDate date, List<KeptLesson> keptHere,
                                               ConflictFacts facts) {
        String what = "%s %s%s не поставлено на %s, %d пара".formatted(facts.groupCode(lesson.getGroupId()),
                facts.disciplineName(lesson.getDisciplineId()),
                lesson.getSubgroupNumber() > 0 ? " (підгр. " + lesson.getSubgroupNumber() + ")" : "",
                UkrainianText.date(date), lesson.getPairNumber());
        if (lesson.getTeacherId() != null) {
            Optional<KeptLesson> busy = keptHere.stream()
                    .filter(KeptLesson::scheduled)
                    .filter(kept -> lesson.getTeacherId().equals(kept.teacherId()))
                    .filter(kept -> !kept.inStream(lesson.getStreamId()))
                    .findFirst();
            if (busy.isPresent()) {
                return Optional.of(ConflictDto.hard(ConflictKind.TEACHER,
                        what + ": викладач " + facts.teacherName(lesson.getTeacherId())
                                + " у цей час веде заняття, змінене розпорядженням",
                        List.of(busy.get().id()), date, lesson.getPairNumber()));
            }
        }
        RoomFact room = facts.room(lesson.getRoomId());
        if (room != null && room.maxParallel() == 1) {
            Optional<KeptLesson> busy = keptHere.stream()
                    .filter(KeptLesson::scheduled)
                    .filter(KeptLesson::roomExclusive)
                    .filter(kept -> Objects.equals(lesson.getRoomId(), kept.roomId()))
                    .filter(kept -> !kept.inStream(lesson.getStreamId()))
                    .findFirst();
            if (busy.isPresent()) {
                return Optional.of(ConflictDto.hard(ConflictKind.ROOM,
                        what + ": аудиторія " + room.name() + " зайнята заняттям, зміненим розпорядженням",
                        List.of(busy.get().id()), date, lesson.getPairNumber()));
            }
        }
        return Optional.empty();
    }

    /**
     * @param template  lessons of the version being published
     * @param facts     catalog facts of the template lessons (groups, rooms, names)
     * @param kept      dated lessons from {@code from} on that stay (see {@link KeptLesson})
     */
    public record Input(UUID semesterId, UUID versionId, List<TemplateLesson> template, StudyCalendar calendar,
                        ConflictFacts facts, List<KeptLesson> kept, LocalDate from, LocalDate to) {
    }

    /**
     * @param coveredByChanges template occurrences left to an applied change in the same cohort slot
     * @param clashes          occurrences not created because a kept lesson took the teacher or the room
     * @param dates            study dates that were materialized
     */
    public record Plan(List<NewLesson> lessons, int coveredByChanges, List<ConflictDto> clashes,
                       List<LocalDate> dates) {
    }

    private record SlotKey(LocalDate date, int pair) {
    }

    private record GroupDay(UUID groupId, LocalDate date) {
    }
}
