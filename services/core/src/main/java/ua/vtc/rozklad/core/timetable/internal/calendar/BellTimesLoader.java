package ua.vtc.rozklad.core.timetable.internal.calendar;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;

/**
 * Bell times of each semester of a {@link StudyCalendar}: the semester's own profile, otherwise the
 * active profile of the college (DOMAIN.md §2, «2 зміни, 60 хв» by default). Without any profile
 * the lessons have no times ({@code null}), which is logged.
 */
@Component
public class BellTimesLoader {

    private static final Logger log = LoggerFactory.getLogger(BellTimesLoader.class);

    private final BellProfileRepository profiles;

    public BellTimesLoader(BellProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** semester id → bell times; every semester of the calendar has an entry. */
    public Map<UUID, BellTimes> forCalendar(StudyCalendar calendar) {
        Map<UUID, BellTimes> byProfile = new HashMap<>();
        Map<UUID, BellTimes> bySemester = new HashMap<>();
        for (SemesterCalendar semester : calendar.semesters()) {
            Optional<UUID> profileId = Optional.ofNullable(semester.bellProfileId()).or(this::activeProfileId);
            BellTimes times = profileId
                    .map(id -> byProfile.computeIfAbsent(id, this::load))
                    .orElseGet(() -> {
                        log.warn("Semester {} has no bell profile and no active profile exists: lessons have no times",
                                semester.semesterId());
                        return BellTimes.none();
                    });
            bySemester.put(semester.semesterId(), times);
        }
        return bySemester;
    }

    private Optional<UUID> activeProfileId() {
        return profiles.findAllByOrderByName().stream()
                .filter(BellProfile::isActive)
                .map(BellProfile::getId)
                .findFirst();
    }

    private BellTimes load(UUID profileId) {
        return profiles.findWithSlotsById(profileId).map(BellTimes::of).orElseGet(BellTimes::none);
    }
}
