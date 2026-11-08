package ua.vtc.rozklad.core.calendar.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudySemester;
import ua.vtc.rozklad.core.settings.SettingsService;

/**
 * Bell times of semesters by the one rule of the calendar module: the semester's own profile,
 * otherwise the default profile from the settings ({@code calendar.activeBellProfileId}, «2 зміни,
 * 60 хв» from the reference data). Without either the lessons have no times, which is logged.
 */
@Component
class BellTimesLoader {

    private static final Logger log = LoggerFactory.getLogger(BellTimesLoader.class);

    private final BellProfileRepository profiles;
    private final SettingsService settings;

    BellTimesLoader(BellProfileRepository profiles, SettingsService settings) {
        this.profiles = profiles;
        this.settings = settings;
    }

    /** The profile in effect for a semester's bell profile id ({@code null} = not set). */
    Optional<UUID> profileIdFor(UUID semesterBellProfileId) {
        return Optional.ofNullable(semesterBellProfileId).or(settings::activeBellProfileId);
    }

    BellTimes forSemester(UUID semesterBellProfileId) {
        return profileIdFor(semesterBellProfileId).map(this::load).orElseGet(() -> {
            log.warn("No bell profile for the semester and no default profile in the settings: lessons have no times");
            return BellTimes.none();
        });
    }

    /** semester id → bell times; every semester of the calendar has an entry. */
    Map<UUID, BellTimes> forCalendar(StudyCalendar calendar) {
        Map<UUID, BellTimes> byProfile = new HashMap<>();
        Map<UUID, BellTimes> bySemester = new HashMap<>();
        for (StudySemester semester : calendar.semesters()) {
            BellTimes times = profileIdFor(semester.bellProfileId())
                    .map(id -> byProfile.computeIfAbsent(id, this::load))
                    .orElseGet(() -> forSemester(null));
            bySemester.put(semester.semesterId(), times);
        }
        return bySemester;
    }

    private BellTimes load(UUID profileId) {
        return profiles.findWithSlotsById(profileId).map(BellTimes::of).orElseGet(BellTimes::none);
    }
}
