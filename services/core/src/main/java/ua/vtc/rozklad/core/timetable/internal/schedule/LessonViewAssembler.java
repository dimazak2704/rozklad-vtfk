package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;

/**
 * Turns joined lesson rows into {@link LessonView}s: the kind comes from the origin and the kind of
 * the change, {@code original} from the change row, {@code reasonText} is the public short reason
 * of the order. A cancelled lesson whose replacement (same change) is in the same view is hidden:
 * the replacement already shows it as the original.
 */
final class LessonViewAssembler {

    private LessonViewAssembler() {
    }

    /** Views of one date's rows in pair order; {@code hideReplaced} removes replaced cancelled originals. */
    static List<LessonView> assemble(List<LessonRow> rows, BellTimes bells, boolean hideReplaced) {
        Set<UUID> replacingChanges = rows.stream()
                .filter(LessonRow::scheduled)
                .map(LessonRow::changeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return rows.stream()
                .filter(row -> !hideReplaced || row.scheduled() || !replacingChanges.contains(row.changeId()))
                .map(row -> view(row, bells))
                .toList();
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
            case "REPLACE_TEACHER", "REPLACE_DISCIPLINE", "DISCIPLINE_FINISHED" -> LessonViewKind.SUBSTITUTED;
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
