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
 * exclusive room is taken by a kept lesson is not created and reported as a hard conflict. Within
 * one version a kept lesson speaks only for its own template lesson: when a parity override makes
 * another template lesson due in the slot, a cancelled one does not hold the slot and a scheduled
 * one is reported.
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
        Map<UUID, TemplateLesson> templateById = new HashMap<>();
        input.template().forEach(lesson -> templateById.put(lesson.getId(), lesson));
        Map<SlotKey, List<KeptLesson>> keptBySlot = new HashMap<>();
        Map<LocalDate, List<KeptLesson>> keptByDate = new HashMap<>();
        input.kept().forEach(lesson -> {
            keptBySlot.computeIfAbsent(new SlotKey(lesson.date(), lesson.pair()), key -> new ArrayList<>()).add(lesson);
            keptByDate.computeIfAbsent(lesson.date(), key -> new ArrayList<>()).add(lesson);
        });

        List<LocalDate> dates = input.calendar().studyDates(input.from(), input.to());
        List<NewLesson> created = new ArrayList<>();
        List<Clash> clashes = new ArrayList<>();
        int covered = 0;
        Map<GroupDay, DayInfo> groupDays = new HashMap<>();
        for (LocalDate date : dates) {
            Parity parity = input.calendar().day(date, null).parity();
            for (TemplateLesson lesson : byWeekday.getOrDefault(date.getDayOfWeek().getValue(), List.of())) {
                if (!due(lesson, date, parity)) {
                    continue;
                }
                DayInfo groupDay = groupDay(input, groupDays, lesson.getGroupId(), date);
                if (!groupDay.studyDay()) {
                    continue;
                }
                List<KeptLesson> keptHere = keptBySlot.getOrDefault(new SlotKey(date, lesson.getPairNumber()), List.of());
                // скасоване заняття іншого тижня (чисельник/знаменник) не тримає слот цього тижня
                if (keptHere.stream().anyMatch(kept -> kept.coversCohort(lesson.getGroupId(), lesson.getSubgroupNumber())
                        && (kept.scheduled() || !forAnotherWeek(kept, input, templateById, date, parity)))) {
                    covered++;
                    continue;
                }
                boolean online = lesson.isOnline() || groupDay.online();
                Optional<ConflictDto> clash = clash(lesson, date, online, keptHere, input.facts());
                if (clash.isPresent()) {
                    clashes.add(new Clash(lesson.getId(), date, clash.get()));
                    continue;
                }
                created.add(newLesson(input, lesson, date, online));
            }
            for (KeptLesson kept : keptByDate.getOrDefault(date, List.of())) {
                if (kept.scheduled() && forAnotherWeek(kept, input, templateById, date, parity)
                        && groupDay(input, groupDays, kept.groupId(), date).studyDay()) {
                    clashes.add(new Clash(kept.id(), date,
                            notDue(kept, templateById.get(kept.root().templateLessonId()), date, input.facts())));
                }
            }
        }
        return new Plan(created, covered, clashes, dates);
    }

    /** Template lessons of a weekday take place in the weeks of their parity. */
    private static boolean due(TemplateLesson lesson, LocalDate date, Parity parity) {
        return lesson.getWeekday() == date.getDayOfWeek().getValue()
                && (lesson.getParity() == Parity.BOTH || lesson.getParity() == parity);
    }

    /**
     * The kept lesson stands for a template lesson of the version being planned that does not take
     * place on that date any more (a parity override turned the week): within one version a kept
     * lesson speaks only for its own template lesson. A lesson of another version, an extra lesson
     * or a block still governs its slot (DOMAIN §6).
     */
    private static boolean forAnotherWeek(KeptLesson kept, Input input, Map<UUID, TemplateLesson> templateById,
                                          LocalDate date, Parity parity) {
        if (!kept.rootedIn(input.versionId())) {
            return false;
        }
        TemplateLesson root = templateById.get(kept.root().templateLessonId());
        return root != null && !due(root, date, parity);
    }

    private static DayInfo groupDay(Input input, Map<GroupDay, DayInfo> groupDays, UUID groupId, LocalDate date) {
        GroupFact group = input.facts().group(groupId);
        return groupDays.computeIfAbsent(new GroupDay(groupId, date), key -> input.calendar().day(date,
                group == null ? null : new GroupScope(group.id(), group.course())));
    }

    /**
     * A scheduled lesson an order or an open decision keeps on a date its template lesson no longer
     * falls on. The refresh does not touch such lessons (DOMAIN §6), so the calendar edit is refused.
     */
    private static ConflictDto notDue(KeptLesson kept, TemplateLesson root, LocalDate date, ConflictFacts facts) {
        String what = "%s %s%s (%s), %s, %d пара".formatted(facts.groupCode(root.getGroupId()),
                facts.disciplineName(root.getDisciplineId()),
                root.getSubgroupNumber() > 0 ? " (підгр. " + root.getSubgroupNumber() + ")" : "",
                root.getParity() == Parity.NUMERATOR ? "чисельник" : "знаменник", UkrainianText.date(date),
                kept.pair());
        return ConflictDto.hard(ConflictKind.GROUP, kept.fromOrder()
                        ? what + ": заняття змінене розпорядженням, а за новою парністю тижня його цього дня немає. "
                        + "Спершу скасуйте цю пару доповненням."
                        : what + ": про заняття є відкрите рішення диспетчера, а за новою парністю тижня його цього "
                        + "дня немає. Спершу скасуйте це рішення.",
                List.of(kept.id()), date, kept.pair());
    }

    /** An online lesson keeps its room for reference but does not occupy it (DOMAIN §5). */
    private NewLesson newLesson(Input input, TemplateLesson lesson, LocalDate date, boolean online) {
        RoomFact room = input.facts().room(lesson.getRoomId());
        return new NewLesson(Ids.newId(), input.semesterId(), input.versionId(), lesson.getId(), date,
                lesson.getPairNumber(), lesson.getGroupId(), lesson.getSubgroupNumber(), lesson.getDisciplineId(),
                lesson.getTeacherId(), lesson.getRoomId(), !online && (room == null || room.maxParallel() == 1),
                lesson.getStreamId(), lesson.getLessonKind(), online);
    }

    /** The teacher or the exclusive room is busy with a kept lesson (the DB would reject both lessons). */
    private static Optional<ConflictDto> clash(TemplateLesson lesson, LocalDate date, boolean online,
                                               List<KeptLesson> keptHere, ConflictFacts facts) {
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
        if (!online && room != null && room.maxParallel() == 1) {
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
     * @param found            occurrences not created because a kept lesson took the teacher or the room,
     *                         and kept lessons whose template lesson no longer falls on their date
     * @param dates            study dates that were materialized
     */
    public record Plan(List<NewLesson> lessons, int coveredByChanges, List<Clash> found, List<LocalDate> dates) {

        public List<ConflictDto> clashes() {
            return found.stream().map(Clash::conflict).toList();
        }
    }

    /**
     * One hard conflict of a plan.
     *
     * @param subjectId the template lesson that was not created, or the kept lesson that is out of place
     */
    public record Clash(UUID subjectId, LocalDate date, ConflictDto conflict) {
    }

    private record SlotKey(LocalDate date, int pair) {
    }

    private record GroupDay(UUID groupId, LocalDate date) {
    }
}
