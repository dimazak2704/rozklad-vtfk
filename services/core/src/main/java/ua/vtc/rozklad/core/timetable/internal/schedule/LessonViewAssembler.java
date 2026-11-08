package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;

/**
 * Turns joined lesson rows into {@link LessonView}s: the kind comes from the origin and the kind of
 * the change, {@code original} from the change row, {@code reasonText} is the public short reason
 * of the order. A cancelled lesson whose replacement (the lesson its change created) is in the same
 * view is hidden: the replacement already shows it as the original, and when an amendment later
 * cancelled the replacement, the cancelled replacement stands for the pair (one row per pair). A
 * block of pairs hides the cancelled lessons of its cohort in its pairs the same way.
 */
final class LessonViewAssembler {

    private LessonViewAssembler() {
    }

    /** Views of one date's rows in pair order; {@code hideReplaced} removes replaced cancelled originals. */
    static List<LessonView> assemble(List<LessonRow> rows, BellTimes bells, boolean hideReplaced) {
        Set<UUID> takingPlace = rows.stream()
                .filter(LessonRow::scheduled)
                .flatMap(row -> Stream.of(row.changeId(), row.createdBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> creating = rows.stream()
                .map(LessonRow::createdBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<LessonRow> blocks = rows.stream()
                .filter(row -> row.scheduled() && row.origin() == LessonOrigin.BLOCK)
                .toList();
        return rows.stream()
                .filter(row -> !hideReplaced || row.scheduled()
                        || !(replaced(row, takingPlace, creating) || underBlock(row, blocks)))
                .map(row -> view(row, bells))
                .toList();
    }

    /**
     * A block of pairs takes the whole pair of its cohort: a lesson cancelled in that pair (by the
     * block or by another change of the same order) is not shown next to it.
     */
    private static boolean underBlock(LessonRow row, List<LessonRow> blocks) {
        return blocks.stream().anyMatch(block -> block.pair() == row.pair()
                && block.group().id().equals(row.group().id())
                && (block.subgroup() == 0 || row.subgroup() == 0 || block.subgroup() == row.subgroup()));
    }

    /** The change that cancelled the row put another lesson into the view (scheduled or cancelled later). */
    private static boolean replaced(LessonRow row, Set<UUID> takingPlace, Set<UUID> creating) {
        UUID cancelledBy = row.changeId();
        return cancelledBy != null && (takingPlace.contains(cancelledBy)
                || (!cancelledBy.equals(row.createdBy()) && creating.contains(cancelledBy)));
    }

    static LessonView view(LessonRow row, BellTimes bells) {
        LessonViewKind kind = kind(row);
        int weekday = row.date().getDayOfWeek().getValue();
        Optional<BellTimes.Slot> slot = bells.slot(weekday, row.pair());
        LocalTime startsAt = slot.map(BellTimes.Slot::startsAt).orElse(null);
        LocalTime endsAt = slot.map(BellTimes.Slot::endsAt).orElse(null);
        if (kind == LessonViewKind.BLOCK && row.change() != null && row.change().pairTo() > row.pair()) {
            // блок пар («1-4») триває до кінця останньої пари блоку
            endsAt = bells.slot(weekday, row.change().pairTo()).map(BellTimes.Slot::endsAt).orElse(endsAt);
        }
        return new LessonView(row.id(), row.pair(), startsAt, endsAt, row.discipline(), row.teacher(), row.room(),
                row.group(), row.subgroup(), kind, row.online(), original(row, kind),
                row.change() == null ? null : printedReason(row.change()),
                row.changeId());
    }

    static LessonViewKind kind(LessonRow row) {
        if (!row.scheduled()) {
            return LessonViewKind.CANCELLED;
        }
        if (row.origin() == LessonOrigin.ADDITIONAL) {
            return LessonViewKind.ADDITIONAL;
        }
        if (row.origin() == LessonOrigin.BLOCK) {
            return LessonViewKind.BLOCK;
        }
        if (row.change() == null) {
            return row.origin() == LessonOrigin.CHANGE ? LessonViewKind.SUBSTITUTED : LessonViewKind.REGULAR;
        }
        return switch (row.change().kind()) {
            case "REPLACE_TEACHER", "REPLACE_DISCIPLINE", "DISCIPLINE_FINISHED", "MERGE_SUBGROUPS" ->
                    LessonViewKind.SUBSTITUTED;
            case "SWAP" -> LessonViewKind.MOVED;
            case "ROOM_CHANGE" -> LessonViewKind.ROOM_CHANGED;
            case "ADDITIONAL" -> LessonViewKind.ADDITIONAL;
            case "BLOCK" -> LessonViewKind.BLOCK;
            default -> row.origin() == LessonOrigin.CHANGE ? LessonViewKind.SUBSTITUTED : LessonViewKind.REGULAR;
        };
    }

    private static String printedReason(LessonRow.Change change) {
        return ReasonCode.printed(ChangeKind.valueOf(change.kind()),
                change.reasonCode() == null ? null : ReasonCode.valueOf(change.reasonCode()), change.reasonText());
    }

    private static LessonView.Original original(LessonRow row, LessonViewKind kind) {
        if (row.change() == null || (kind != LessonViewKind.SUBSTITUTED && kind != LessonViewKind.MOVED
                && kind != LessonViewKind.ROOM_CHANGED)) {
            return null;
        }
        LessonRow.Change change = row.change();
        Integer pair = movedFrom(row);
        if (change.originalDiscipline() == null && change.originalTeacher() == null && change.originalRoom() == null
                && pair == null) {
            return null;
        }
        return new LessonView.Original(change.originalDiscipline(), change.originalTeacher(), change.originalRoom(),
                pair);
    }

    /**
     * Pair the lesson came from: for a SWAP the pair of the moved lesson, else the original lesson's
     * pair, else the other end of the change's pair range.
     */
    private static Integer movedFrom(LessonRow row) {
        LessonRow.Change change = row.change();
        if (change.swapPair() != null) {
            return change.swapPair() != row.pair() ? change.swapPair() : null;
        }
        if (change.originalPair() != null) {
            return change.originalPair() != row.pair() ? change.originalPair() : null;
        }
        if (change.pairFrom() != row.pair()) {
            return change.pairFrom();
        }
        return change.pairTo() != row.pair() ? change.pairTo() : null;
    }
}
