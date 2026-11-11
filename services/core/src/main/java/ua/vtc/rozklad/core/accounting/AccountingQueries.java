package ua.vtc.rozklad.core.accounting;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Read API of the accounting module for other modules (substitution ranks disciplines behind plan). */
public interface AccountingQueries {

    /** Progress of every discipline that has load items for the group in the semester, by discipline id. */
    Map<UUID, DisciplineProgress> progressOfGroup(UUID semesterId, UUID groupId);

    /**
     * {@link #progressOfGroup} for several groups of one semester at once: the semester, the bell times
     * and the hours per pair are read once instead of once per group. Keys follow {@code groupIds}.
     */
    Map<UUID, Map<UUID, DisciplineProgress>> progressOfGroups(UUID semesterId, Collection<UUID> groupIds);

    /** Academic hours counted for one pair (setting {@code accounting.hoursPerPair}, default 2). */
    int hoursPerPair();
}
