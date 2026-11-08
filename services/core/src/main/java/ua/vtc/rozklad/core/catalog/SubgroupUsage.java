package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Extension point: modules that refer to subgroups by number (load, timetable) tell the catalog
 * whether a subgroup is still used, so deleting it is a 409 instead of leaving dangling numbers.
 * Subgroups have no foreign keys because rows store only {@code subgroup_number}.
 */
public interface SubgroupUsage {

    /** Ukrainian descriptions of usages, e.g. «навантаження: 2 записи»; empty when unused. */
    List<String> usagesOf(UUID groupId, int subgroupNumber);
}
