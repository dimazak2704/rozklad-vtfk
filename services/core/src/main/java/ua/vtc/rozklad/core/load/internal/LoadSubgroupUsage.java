package ua.vtc.rozklad.core.load.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.SubgroupUsage;
import ua.vtc.rozklad.core.load.LoadItemTeacherRepository;

/** A subgroup with a teacher in some load item is still in use (catalog refuses to delete it). */
@Component
class LoadSubgroupUsage implements SubgroupUsage {

    private final LoadItemTeacherRepository teachers;

    LoadSubgroupUsage(LoadItemTeacherRepository teachers) {
        this.teachers = teachers;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID groupId, int subgroupNumber) {
        long count = teachers.countForSubgroup(groupId, subgroupNumber);
        return count == 0 ? List.of() : List.of("навантаження, записів: %d".formatted(count));
    }
}
