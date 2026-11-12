package ua.vtc.rozklad.core.substitution.internal.effect;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;

/**
 * A substitute must not be absent at (date, pair) (DOMAIN §7). The rule is checked when a change is
 * created or edited, when it is confirmed and once more when its order is published, because the
 * absence may be registered after the substitute agreed. A teacher who keeps their own lesson (the
 * original teacher of the change) is not a substitute.
 */
@Component
public class AbsentSubstitutes {

    private final AbsenceRepository absences;
    private final CatalogLookup catalog;

    public AbsentSubstitutes(AbsenceRepository absences, CatalogLookup catalog) {
        this.absences = absences;
        this.catalog = catalog;
    }

    /** {@code true} when the change assigns a teacher other than the original one. */
    public static boolean hasSubstitute(Change change) {
        return change.getNewTeacherId() != null && !change.getNewTeacherId().equals(change.getOrigTeacherId());
    }

    /** UNAVAILABLE conflicts, one per pair of the change at which its substitute is absent. */
    public List<ConflictDto> conflicts(Change change) {
        return conflicts(List.of(change));
    }

    public List<ConflictDto> conflicts(Collection<Change> changes) {
        // одна вибірка на дату для всіх замінників (публікація перевіряє всі зміни розпорядження)
        Map<LocalDate, List<Absence>> byDate = new HashMap<>();
        List<ConflictDto> conflicts = new ArrayList<>();
        for (Change change : changes) {
            if (!hasSubstitute(change)) {
                continue;
            }
            List<Absence> active = byDate.computeIfAbsent(change.getDate(),
                    date -> absences.findActiveOverlapping(date, date, null)).stream()
                    .filter(absence -> absence.getTeacherId().equals(change.getNewTeacherId()))
                    .toList();
            if (active.isEmpty()) {
                continue;
            }
            String name = null;
            for (int pair = change.getPairFrom(); pair <= change.getPairTo(); pair++) {
                int slot = pair;
                if (active.stream().anyMatch(absence -> absence.covers(change.getDate(), slot))) {
                    name = Objects.requireNonNullElseGet(name,
                            () -> catalog.teacher(change.getNewTeacherId()).getShortName());
                    conflicts.add(ConflictDto.hard(ConflictKind.UNAVAILABLE, "%s відсутній %s на %d парі".formatted(name,
                            change.getDate().format(SubstitutionCalendar.DATE), slot), List.of(), change.getDate(), slot));
                }
            }
        }
        return conflicts;
    }
}
