package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;

/** Admin texts name the teacher as core does, then as the binding remembers, then generically. */
class TeacherNamesTest {

    private static final UUID TEACHER = UUID.fromString("5b2c4d1e-0f3a-4b5c-8d9e-1a2b3c4d5e6f");
    private static final UUID OTHER = UUID.fromString("5b2c4d1e-0f3a-4b5c-8d9e-1a2b3c4d5e70");

    private final CoreQueries queries = mock(CoreQueries.class);
    private final ChatBindings bindings = mock(ChatBindings.class);
    private final TeacherNames names = new TeacherNames(queries, bindings);

    @Test
    void takesTheNameFromCore() {
        when(queries.teacher(TEACHER)).thenReturn(Optional.of(new PublicTeacher(TEACHER, "Непийвода М.В.", "")));

        assertThat(names.of(TEACHER)).isEqualTo("Непийвода М.В.");
        verify(bindings, never()).teacherChat(TEACHER);
    }

    @Test
    void fallsBackToTheBindingWhenCoreIsDownOrHasNoName() {
        when(bindings.teacherChat(TEACHER)).thenReturn(Optional.of(
                ChatBinding.teacher(1L, TEACHER, "Непийвода", Instant.EPOCH)));
        when(queries.teacher(TEACHER)).thenThrow(new UpstreamUnavailableException("core", "down", null));
        assertThat(names.of(TEACHER)).isEqualTo("Непийвода");

        when(queries.teacher(OTHER)).thenReturn(Optional.of(new PublicTeacher(OTHER, " ", null)));
        when(bindings.teacherChat(OTHER)).thenReturn(Optional.empty());
        assertThat(names.of(OTHER)).isEqualTo(TeacherNames.UNKNOWN_TEACHER);
    }

    @Test
    void anUnknownTeacherIsNamedGenerically() {
        assertThat(names.of((UUID) null)).isEqualTo(TeacherNames.UNKNOWN_TEACHER);
    }

    @Test
    void nameUsesTheLookupDoneBeforeLockingAndLooksUpOnlyTheMissingTeacher() {
        when(queries.teacher(TEACHER)).thenReturn(Optional.of(new PublicTeacher(TEACHER, "Непийвода М.В.", "")));
        when(queries.teacher(OTHER)).thenReturn(Optional.of(new PublicTeacher(OTHER, "Кренцін М.Д.", "")));
        Map<UUID, String> before = names.of(List.of(TEACHER));

        assertThat(before).containsExactly(Map.entry(TEACHER, "Непийвода М.В."));
        assertThat(names.name(before, TEACHER)).isEqualTo("Непийвода М.В.");
        assertThat(names.name(before, OTHER)).isEqualTo("Кренцін М.Д.");
        verify(queries).teacher(TEACHER);
    }
}
