package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;

/** Demo accounts are linked to the imported teachers «Кренцін» and «Непийвода» only when unambiguous. */
class DemoAccountLinksTest {

    private static final UUID KRENTSIN_ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID NEPYIVODA_ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000003");

    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final DemoAccountLinks links = new DemoAccountLinks(teachers);

    @Test
    void linksImportedTeachersBySurname() {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        Teacher nepyivoda = new Teacher("Непийвода", "Непийвода М.В.", "Непийвода");
        given(teachers.findAll()).willReturn(List.of(krentsin, nepyivoda));

        links.link();

        assertThat(krentsin.getKeycloakUserId()).isEqualTo(KRENTSIN_ACCOUNT);
        assertThat(nepyivoda.getKeycloakUserId()).isEqualTo(NEPYIVODA_ACCOUNT);
    }

    @Test
    void ambiguousSurnameOrTakenAccountIsLeftAlone() {
        Teacher first = new Teacher("Непийвода", "Непийвода М.В.", "Непийвода");
        Teacher second = new Teacher("Непийвода", "Непийвода О.", "Непийвода");
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        Teacher alreadyLinked = new Teacher("Інший", "Інший І.", "Інший");
        alreadyLinked.setKeycloakUserId(KRENTSIN_ACCOUNT);
        given(teachers.findAll()).willReturn(List.of(first, second, krentsin, alreadyLinked));

        links.link();

        assertThat(first.getKeycloakUserId()).isNull();
        assertThat(second.getKeycloakUserId()).isNull();
        assertThat(krentsin.getKeycloakUserId()).isNull();
    }
}
