package ua.vtc.rozklad.notification.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** The catalog cache (1 h) must not hide a teacher or group the dispatcher has just created. */
class CoreQueriesIT extends IntegrationTest {

    private static final UUID NEW_TEACHER = UUID.fromString("0b7c1d2e-3f40-4a51-8b62-7c83d94ea5f6");
    private static final UUID NEW_GROUP = UUID.fromString("1c8d2e3f-4051-4b62-9c73-8d94ea5fb607");

    @Autowired
    private CoreQueries queries;

    @Test
    void teacherCreatedAfterTheCatalogWasCachedIsFound() {
        assertThat(queries.teacher(CoreFixtures.TEACHER_KRENTSIN)).isPresent();
        core.addTeacher(new PublicTeacher(NEW_TEACHER, "Сумісник О.П.", "Сумісник"));

        assertThat(queries.teacher(NEW_TEACHER)).get().extracting(PublicTeacher::label).isEqualTo("Сумісник О.П.");
        int calls = core.teacherCalls();
        assertThat(queries.teacher(NEW_TEACHER)).isPresent();
        assertThat(queries.teacher(CoreFixtures.TEACHER_NEPYIVODA)).isPresent();
        assertThat(core.teacherCalls()).as("known ids are served from the cache").isEqualTo(calls);
    }

    @Test
    void groupCreatedAfterTheCatalogWasCachedIsFound() {
        assertThat(queries.group(CoreFixtures.GROUP_4KI1)).isPresent();
        core.addGroup(new PublicGroup(NEW_GROUP, "1КІ9", 1, 1));

        assertThat(queries.group(NEW_GROUP)).get().extracting(PublicGroup::code).isEqualTo("1КІ9");
        assertThat(queries.groups()).extracting(PublicGroup::code).contains("1КІ9");
    }

    @Test
    void unknownIdIsStillEmptyAfterOneReload() {
        assertThat(queries.teacher(NEW_TEACHER)).isEmpty();
        assertThat(queries.group(NEW_GROUP)).isEmpty();
    }
}
