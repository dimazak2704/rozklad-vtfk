package ua.vtc.rozklad.core.order.internal.draft;

import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;

/**
 * A confirmed change with every text already resolved, the input of {@link OrderRowComposer}.
 * {@code from*} describe the lesson as scheduled, {@code to*} what happens instead (for a BLOCK,
 * the block itself), {@code room} the room of the resulting lesson.
 *
 * @param movedFromPair    SWAP: the pair the moved lesson leaves
 * @param pairsAsScheduled BLOCK: other pairs of the group that day that stay as scheduled
 */
public record ChangeRow(
        UUID changeId,
        ChangeKind kind,
        String groupCode,
        int course,
        int subgroup,
        int pairFrom,
        int pairTo,
        String fromDiscipline,
        String fromTeacher,
        String reason,
        String toDiscipline,
        String toTeacher,
        String room,
        Integer movedFromPair,
        List<Integer> pairsAsScheduled) {

    public ChangeRow {
        pairsAsScheduled = pairsAsScheduled == null ? List.of() : List.copyOf(pairsAsScheduled);
    }
}
