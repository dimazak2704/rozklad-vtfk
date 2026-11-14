package ua.vtc.rozklad.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.notification.core.CoreClient.TeacherStatus;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** A teacher who left loses his Telegram chat overnight; nobody else does, whatever core's list says. */
class InactiveTeacherChatsIT extends IntegrationTest {

    @Autowired
    private InactiveTeacherChats inactive;

    @Test
    void inactiveAndRemovedTeachersLoseTheirChats() {
        teacherChat(8101, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        teacherChat(8102, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
        UUID removed = UUID.randomUUID();
        teacherChat(8103, removed, "Звільнений В.В.");
        core.setTeacherStatus(CoreFixtures.TEACHER_NEPYIVODA, TeacherStatus.INACTIVE);
        core.setTeacherStatus(removed, TeacherStatus.GONE);

        assertThat(inactive.unlinkInactive()).isEqualTo(2);

        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_NEPYIVODA)).isEmpty();
        assertThat(bindings.teacherChat(removed)).isEmpty();
        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_KRENTSIN)).isPresent();
        assertThat(inactive.unlinkInactive()).isZero();
    }

    @Test
    void aTeacherMissingFromThePublicListButActiveInCoreKeepsTheChat() {
        UUID newTeacher = UUID.randomUUID();
        teacherChat(8104, newTeacher, "Новий Н.Н.");

        assertThat(inactive.unlinkInactive()).isZero();

        assertThat(bindings.teacherChat(newTeacher)).isPresent();
    }

    @Test
    void coreDownUnlinksNobody() {
        teacherChat(8101, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        core.setTeacherStatus(CoreFixtures.TEACHER_NEPYIVODA, TeacherStatus.INACTIVE);
        core.setUnavailable(true);

        inactive.nightly();

        assertThat(bindings.teacherChat(CoreFixtures.TEACHER_NEPYIVODA)).isPresent();
    }
}
