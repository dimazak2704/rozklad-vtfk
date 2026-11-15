package ua.vtc.rozklad.notification.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.notification.core.CoreDtos.PeriodKindRef;
import ua.vtc.rozklad.notification.core.CoreDtos.Practice;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;

/** {@code DayDto.practices[]} of a current core (with {@code kind} and {@code room}) and of an older one. */
class CoreDtosPracticeJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsKindAndRoomOfASuspendPeriod() {
        ScheduleDay day = JSON.readValue("""
                {"date":"2026-11-16","weekday":1,"weekdayLabel":"ПОНЕДІЛОК","parity":"NUMERATOR","studyDay":false,
                 "note":"Основи національного спротиву","lessons":[],
                 "practices":[{"periodId":"7c0e8a52-1d2b-4c3d-9e4f-5a6b7c8d9e0f","title":"Основи національного спротиву",
                   "startsOn":"2026-11-16","endsOn":"2026-11-27","supervisors":[],
                   "groups":[{"id":"1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d","code":"4КІ1","course":4}],"courses":[],
                   "kind":{"code":"НС","name":"Національний спротив","color":null,"effect":"SUSPEND","extra":1},
                   "room":{"id":"2a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d","name":"401","type":"CLASSROOM"}}]}""",
                ScheduleDay.class);

        Practice practice = day.practices().getFirst();
        assertThat(practice.kind()).isEqualTo(new PeriodKindRef("НС", "Національний спротив", null, "SUSPEND"));
        assertThat(practice.room().name()).isEqualTo("401");
        assertThat(practice.groups()).singleElement().satisfies(group -> assertThat(group.code()).isEqualTo("4КІ1"));
    }

    @Test
    void olderCoreWithoutKindAndRoomStillReads() {
        ScheduleDay day = JSON.readValue("""
                {"date":"2026-10-19","weekday":1,"studyDay":false,"note":null,"lessons":[],
                 "practices":[{"periodId":"7c0e8a52-1d2b-4c3d-9e4f-5a6b7c8d9e0f","title":"Електрорадіомонтажна практика",
                   "supervisors":null,"groups":null,"courses":[2]}]}""", ScheduleDay.class);

        Practice practice = day.practices().getFirst();
        assertThat(practice.kind()).isNull();
        assertThat(practice.room()).isNull();
        assertThat(practice.supervisors()).isEmpty();
        assertThat(practice.courses()).containsExactly(2);
    }
}
