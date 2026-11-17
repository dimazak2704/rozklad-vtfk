package ua.vtc.rozklad.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeCoreClient;
import ua.vtc.rozklad.notification.web.BusinessRuleException;

/**
 * {@code DELETE /api/notify/bindings/me}: the teacher comes from the caller's own token, so the cabinet can unlink
 * only that teacher's TEACHER chat, never another binding.
 */
class MyTelegramBindingTest {

    private final FakeCoreClient core = new FakeCoreClient();
    private final ChatBindings bindings = mock(ChatBindings.class);
    private final MyTelegramBinding myBinding = new MyTelegramBinding(core, bindings);

    @Test
    void unlinksOnlyTheTeacherOfTheCallersToken() {
        // "teacher-token" belongs to Непийвода in the fake core
        when(bindings.unlinkTeacher(CoreFixtures.TEACHER_NEPYIVODA)).thenReturn(Optional.of(
                ChatBinding.teacher(2001, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.", Instant.EPOCH)));

        myBinding.unlink("teacher-token");

        verify(bindings).unlinkTeacher(CoreFixtures.TEACHER_NEPYIVODA);
        verify(bindings, never()).unlinkTeacher(CoreFixtures.TEACHER_KRENTSIN);
        verify(bindings, never()).delete(any());
    }

    @Test
    void nothingBoundIsStillASuccess() {
        when(bindings.unlinkTeacher(CoreFixtures.TEACHER_NEPYIVODA)).thenReturn(Optional.empty());

        myBinding.unlink("teacher-token");

        verify(bindings).unlinkTeacher(CoreFixtures.TEACHER_NEPYIVODA);
    }

    @Test
    void anAccountWithoutATeacherUnlinksNothing() {
        assertThatThrownBy(() -> myBinding.unlink("unlinked-token"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(error -> ((BusinessRuleException) error).getCode())
                .isEqualTo("teacher-not-linked");
        verifyNoInteractions(bindings);
    }

    @Test
    void coreDownUnlinksNothing() {
        core.setUnavailable(true);

        assertThatThrownBy(() -> myBinding.unlink("teacher-token")).isInstanceOf(UpstreamUnavailableException.class);
        verifyNoInteractions(bindings);
    }

    @Test
    void theStatusReadsTheSameTeacher() {
        when(bindings.teacherChat(CoreFixtures.TEACHER_NEPYIVODA)).thenReturn(Optional.empty());

        MyTelegramBinding.Status status = myBinding.of("teacher-token");

        assertThat(status.teacherId()).isEqualTo(CoreFixtures.TEACHER_NEPYIVODA);
        assertThat(status.binding()).isEmpty();
    }
}
