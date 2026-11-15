package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * What a solved generation changed against its base version (genfix M2/B8), stored in
 * {@code generation_job.outcome}; the UI shows «Переставлено {moved} з {total} пар» (+ «, ще {roomChanged} — в
 * іншій аудиторії»).
 *
 * @param moved       units whose weekday, pair or week differs from their base lesson (unplaced ones too)
 * @param roomChanged units kept in their base slot whose base lesson had a room and that got another one
 * @param total       units that had a base lesson ({@code PlanUnit.initial})
 * @param perGroup    the same per group of the scope, ordered by course and code
 */
public record GenerationOutcome(int moved, int roomChanged, int total, List<GroupOutcome> perGroup) {

    public GenerationOutcome {
        perGroup = perGroup == null ? List.of() : List.copyOf(perGroup);
    }

    public record GroupOutcome(GroupRef group, int moved, int total) {
    }

    /**
     * @param roomOf the room a placed unit gets
     * @param groups group references by id (a group missing there is left out of {@code perGroup})
     */
    static GenerationOutcome of(GeneratedLessonsBuilder.Result result,
                                Function<GeneratedLessonsBuilder.Placement, UUID> roomOf, Map<UUID, GroupRef> groups) {
        int moved = 0;
        int roomChanged = 0;
        int total = 0;
        Map<UUID, int[]> byGroup = new LinkedHashMap<>();
        for (GeneratedLessonsBuilder.Placement placement : result.placements()) {
            ProblemSnapshot.PinnedSlot initial = placement.unit().initial();
            if (initial == null) {
                continue;
            }
            int[] counts = byGroup.computeIfAbsent(placement.unit().groupId(), id -> new int[2]);
            total++;
            counts[1]++;
            boolean sameSlot = initial.weekday() == placement.weekday() && initial.pair() == placement.pair()
                    && initial.parity() == placement.parity();
            if (!sameSlot) {
                moved++;
                counts[0]++;
            } else if (initial.roomId() != null && !Objects.equals(initial.roomId(), roomOf.apply(placement))) {
                roomChanged++;
            }
        }
        for (PlanUnit unit : result.unplaced()) {
            if (unit.initial() != null) {
                int[] counts = byGroup.computeIfAbsent(unit.groupId(), id -> new int[2]);
                total++;
                moved++;
                counts[0]++;
                counts[1]++;
            }
        }
        List<GroupOutcome> perGroup = new ArrayList<>();
        byGroup.forEach((groupId, counts) -> {
            GroupRef group = groups.get(groupId);
            if (group != null) {
                perGroup.add(new GroupOutcome(group, counts[0], counts[1]));
            }
        });
        perGroup.sort(Comparator.comparingInt((GroupOutcome row) -> row.group().course())
                .thenComparing(row -> row.group().code()));
        return new GenerationOutcome(moved, roomChanged, total, perGroup);
    }
}
