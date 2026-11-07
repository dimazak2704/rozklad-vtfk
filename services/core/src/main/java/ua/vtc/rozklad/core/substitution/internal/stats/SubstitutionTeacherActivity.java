package ua.vtc.rozklad.core.substitution.internal.stats;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.accounting.TeacherActivityProvider;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Teacher statistics from confirmed and applied changes (DOMAIN §9). A substitution is a change in
 * which the teacher takes a colleague's pair (same or another discipline); a missed pair is an own
 * pair that was given to someone else, moved away or cancelled. Moving one's own lesson (SWAP),
 * extra pairs and blocks are not substitutions; «Вичит.» without a replacement is not a miss.
 */
@Service
@Transactional(readOnly = true)
class SubstitutionTeacherActivity implements TeacherActivityProvider {

    private static final Set<ChangeStatus> DONE = EnumSet.of(ChangeStatus.CONFIRMED, ChangeStatus.APPLIED);
    private static final Set<ChangeKind> TAKES_OVER = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.DISCIPLINE_FINISHED);
    private static final Set<ChangeKind> LOSES_PAIR = EnumSet.of(ChangeKind.REPLACE_TEACHER,
            ChangeKind.REPLACE_DISCIPLINE, ChangeKind.CANCEL, ChangeKind.SWAP);

    private final ChangeRepository changes;

    SubstitutionTeacherActivity(ChangeRepository changes) {
        this.changes = changes;
    }

    @Override
    public List<TeacherActivity> activityBetween(LocalDate from, LocalDate to) {
        Map<UUID, Counter> counters = new LinkedHashMap<>();
        for (Change change : changes.findAllByDateBetweenAndStatusIn(from, to, DONE)) {
            UUID original = change.getOrigTeacherId();
            UUID assigned = change.getNewTeacherId();
            boolean otherTeacher = assigned != null && !assigned.equals(original);
            if (TAKES_OVER.contains(change.getKind()) && original != null && otherTeacher) {
                Counter counter = counters.computeIfAbsent(assigned, id -> new Counter());
                counter.substitutions++;
                counter.substitutedPairs += change.pairCount();
            }
            if (LOSES_PAIR.contains(change.getKind()) && original != null && (assigned == null || otherTeacher)) {
                counters.computeIfAbsent(original, id -> new Counter()).missedPairs += change.pairCount();
            }
        }
        return counters.entrySet().stream()
                .map(entry -> new TeacherActivity(entry.getKey(), entry.getValue().substitutions,
                        entry.getValue().substitutedPairs, entry.getValue().missedPairs))
                .toList();
    }

    private static final class Counter {
        private int substitutions;
        private int substitutedPairs;
        private int missedPairs;
    }
}
