package ua.vtc.rozklad.core.order.internal.draft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.core.order.OrderItemContent;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * Turns confirmed changes into the rows of the college order (DOMAIN §8) — a pure function.
 * <ul>
 *   <li>REPLACEMENT — both sides filled (replacement of a teacher or a discipline; a SWAP is a
 *       replacement in the free slot plus «Пари не буде» in the pair the moved lesson leaves);</li>
 *   <li>CANCELLATION — right side «Пари не буде»;</li>
 *   <li>ADDITIONAL — left side «ДОДАТКОВО»;</li>
 *   <li>BLOCK — pairs «1-4», the block on the left plus the room; the group's other pairs that day
 *       follow as AS_SCHEDULED rows («За розкладом», as in order №105);</li>
 *   <li>FINISHED — reason «Вичит.», right side only when another discipline takes the pair;</li>
 *   <li>ROOM_CHANGE — group, pair and room in the «ЗАМІНА АУДИТОРІЙ» section;</li>
 *   <li>MERGE_SUBGROUPS — the absent teacher's subgroup on the left («(п.)»), on the right the
 *       teacher who takes both subgroups and the room (printed «Об'єднання підгруп: …»).</li>
 * </ul>
 * Rows of different subgroups of one group and pair with the same type and reason merge into one
 * row («Харченко О./Михальчук», «304/309»); a row of a single subgroup marks its teacher «(п.)».
 * Rows are sorted by course, group code, pair.
 */
public final class OrderRowComposer {

    private OrderRowComposer() {
    }

    /** Rows of the main table and of the room-changes section, in print order. */
    public record Rows(List<OrderItemContent> changes, List<OrderItemContent> roomChanges) {

        public Rows {
            changes = List.copyOf(changes);
            roomChanges = List.copyOf(roomChanges);
        }
    }

    public static Rows compose(List<ChangeRow> changes) {
        List<Draft> main = new ArrayList<>();
        List<Draft> rooms = new ArrayList<>();
        for (ChangeRow change : changes) {
            switch (change.kind()) {
                case REPLACE_TEACHER, REPLACE_DISCIPLINE -> main.add(Draft.of(change, change.pairFrom(),
                        change.pairTo(), OrderRowType.REPLACEMENT, change.fromDiscipline(), change.fromTeacher(),
                        change.reason(), change.toDiscipline(), change.toTeacher(), change.room()));
                case CANCEL -> main.add(Draft.of(change, change.pairFrom(), change.pairTo(),
                        OrderRowType.CANCELLATION, change.fromDiscipline(), change.fromTeacher(), change.reason(),
                        OrderTexts.NO_PAIR, null, null));
                case DISCIPLINE_FINISHED -> main.add(Draft.of(change, change.pairFrom(), change.pairTo(),
                        OrderRowType.FINISHED, change.fromDiscipline(), change.fromTeacher(), change.reason(),
                        change.toDiscipline(), change.toTeacher(), change.toDiscipline() == null ? null : change.room()));
                case SWAP -> {
                    main.add(Draft.of(change, change.pairFrom(), change.pairTo(), OrderRowType.REPLACEMENT,
                            change.fromDiscipline(), change.fromTeacher(), change.reason(), change.toDiscipline(),
                            change.toTeacher(), change.room()));
                    int vacated = Objects.requireNonNull(change.movedFromPair(), "movedFromPair");
                    main.add(Draft.of(change, vacated, vacated, OrderRowType.CANCELLATION, change.toDiscipline(),
                            change.toTeacher(), null, OrderTexts.NO_PAIR, null, null));
                }
                case ADDITIONAL -> main.add(Draft.of(change, change.pairFrom(), change.pairTo(),
                        OrderRowType.ADDITIONAL, OrderTexts.ADDITIONAL, null, null, change.toDiscipline(),
                        change.toTeacher(), change.room()));
                case BLOCK -> {
                    main.add(Draft.of(change, change.pairFrom(), change.pairTo(), OrderRowType.BLOCK,
                            change.toDiscipline(), change.toTeacher(), null, null, null, change.room()));
                    change.pairsAsScheduled().forEach(pair -> main.add(Draft.asScheduled(change, pair)));
                }
                case MERGE_SUBGROUPS -> main.add(Draft.of(change, change.pairFrom(), change.pairTo(),
                        OrderRowType.MERGE_SUBGROUPS, change.fromDiscipline(), change.fromTeacher(), change.reason(),
                        change.toDiscipline(), change.toTeacher(), change.room()));
                case ROOM_CHANGE -> rooms.add(Draft.of(change, change.pairFrom(), change.pairTo(),
                        OrderRowType.ROOM_CHANGE, null, null, null, null, null, change.room()));
            }
        }
        return new Rows(finish(main), finish(rooms));
    }

    private static List<OrderItemContent> finish(List<Draft> drafts) {
        Comparator<String> alphabetical = UkrainianText.collator();
        return mergeSubgroups(drafts).stream()
                .sorted(Comparator.comparingInt(Draft::course)
                        .thenComparing(Draft::groupCode, alphabetical)
                        .thenComparingInt(Draft::pairFrom)
                        .thenComparingInt(draft -> rank(draft.rowType()))
                        .thenComparingInt(Draft::subgroup))
                .map(Draft::content)
                .toList();
    }

    /** Merges rows of different subgroups of one group and pair; marks the rest «(п.)». */
    private static List<Draft> mergeSubgroups(List<Draft> drafts) {
        Map<String, List<Draft>> bySlot = new LinkedHashMap<>();
        List<Draft> result = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.subgroup() == 0 || draft.rowType() == OrderRowType.AS_SCHEDULED) {
                result.add(draft);
            } else {
                bySlot.computeIfAbsent(draft.groupCode() + "|" + draft.pairLabel() + "|" + draft.rowType() + "|"
                        + draft.reason(), key -> new ArrayList<>()).add(draft);
            }
        }
        for (List<Draft> slot : bySlot.values()) {
            List<Draft> ordered = slot.stream().sorted(Comparator.comparingInt(Draft::subgroup)).toList();
            boolean severalSubgroups = ordered.stream().map(Draft::subgroup).distinct().count() > 1;
            result.add(severalSubgroups ? Draft.merge(ordered) : ordered.getFirst().markedOneSubgroup());
            if (!severalSubgroups && ordered.size() > 1) {
                ordered.subList(1, ordered.size()).forEach(draft -> result.add(draft.markedOneSubgroup()));
            }
        }
        return result;
    }

    /** A block comes before other rows starting with the same pair. */
    private static int rank(OrderRowType type) {
        return type == OrderRowType.BLOCK ? 0 : 1;
    }

    private record Draft(String groupCode, int course, int subgroup, int pairFrom, String pairLabel,
                         OrderRowType rowType, String fromDiscipline, String fromTeacher, String reason,
                         String toDiscipline, String toTeacher, String room, UUID changeId) {

        static Draft of(ChangeRow change, int pairFrom, int pairTo, OrderRowType type, String fromDiscipline,
                        String fromTeacher, String reason, String toDiscipline, String toTeacher, String room) {
            return new Draft(change.groupCode(), change.course(), change.subgroup(), pairFrom,
                    OrderTexts.pairLabel(pairFrom, pairTo), type, fromDiscipline, fromTeacher, reason, toDiscipline,
                    toTeacher, room, change.changeId());
        }

        static Draft asScheduled(ChangeRow block, int pair) {
            return new Draft(block.groupCode(), block.course(), 0, pair, OrderTexts.pairLabel(pair, pair),
                    OrderRowType.AS_SCHEDULED, OrderTexts.AS_SCHEDULED, null, null, null, null, null,
                    block.changeId());
        }

        /** Row of one subgroup: its teacher gets «(п.)» (the new teacher for an extra pair). */
        Draft markedOneSubgroup() {
            if (rowType == OrderRowType.ADDITIONAL) {
                return new Draft(groupCode, course, subgroup, pairFrom, pairLabel, rowType, fromDiscipline,
                        fromTeacher, reason, toDiscipline, marked(toTeacher), room, changeId);
            }
            if (rowType == OrderRowType.ROOM_CHANGE) {
                return this;
            }
            return new Draft(groupCode, course, subgroup, pairFrom, pairLabel, rowType, fromDiscipline,
                    marked(fromTeacher), reason, toDiscipline, toTeacher, room, changeId);
        }

        static Draft merge(List<Draft> subgroups) {
            Draft first = subgroups.getFirst();
            return new Draft(first.groupCode(), first.course(), first.subgroup(), first.pairFrom(), first.pairLabel(),
                    first.rowType(), joined(subgroups, Draft::fromDiscipline), joined(subgroups, Draft::fromTeacher),
                    first.reason(), joined(subgroups, Draft::toDiscipline), joined(subgroups, Draft::toTeacher),
                    joined(subgroups, Draft::room), first.changeId());
        }

        OrderItemContent content() {
            return new OrderItemContent(groupCode, pairLabel, rowType, fromDiscipline, fromTeacher, reason,
                    toDiscipline, toTeacher, room, changeId);
        }

        private static String marked(String teacher) {
            return teacher == null ? null : teacher + OrderTexts.ONE_SUBGROUP;
        }

        /** «Ін.мова» when both subgroups have the same value, «304/309» otherwise. */
        private static String joined(List<Draft> subgroups, Function<Draft, String> field) {
            List<String> values = subgroups.stream().map(field).filter(Objects::nonNull).distinct().toList();
            return values.isEmpty() ? null : String.join("/", values);
        }
    }
}
