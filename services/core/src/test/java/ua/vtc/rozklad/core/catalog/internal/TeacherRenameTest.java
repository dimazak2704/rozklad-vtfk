package ua.vtc.rozklad.core.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherDto;
import ua.vtc.rozklad.core.catalog.internal.TeacherDtos.TeacherRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/**
 * The admin edits a teacher's full name, short name («Кренцін М.Д.») and public name: empty forms
 * are derived from the new name, the short name stays unique among teachers and their aliases.
 */
class TeacherRenameTest {

    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final TeacherAliasRepository aliases = mock(TeacherAliasRepository.class);
    private final TeacherService service = new TeacherService(teachers, aliases, mock(CycleCommissionRepository.class));

    private Teacher krentsin;
    private Teacher koval;

    @BeforeEach
    void catalog() {
        krentsin = new Teacher("Кренцін", "Кренцін", "Кренцін");
        koval = new Teacher("Коваль", "Коваль О.", "Коваль");
        given(teachers.findById(krentsin.getId())).willReturn(Optional.of(krentsin));
        given(teachers.findAll()).willReturn(List.of(krentsin, koval));
        given(aliases.findByNormalized(anyString())).willReturn(Optional.empty());
    }

    @Test
    void newFullNameWithExplicitShortAndPublicNames() {
        TeacherDto saved = service.update(krentsin.getId(),
                request("Кренцин", "Микола", "Дмитрович", "Кренцин М. Д.", "Кренцин"));

        assertThat(saved).extracting(TeacherDto::lastName, TeacherDto::firstName, TeacherDto::middleName,
                        TeacherDto::shortName, TeacherDto::displayName)
                .containsExactly("Кренцин", "Микола", "Дмитрович", "Кренцин М. Д.", "Кренцин");
    }

    @Test
    void emptyShortAndPublicNamesFollowTheNewName() {
        TeacherDto saved = service.update(krentsin.getId(), request("Кренцин", "Микола", "Дмитрович", " ", null));

        assertThat(saved.shortName()).isEqualTo("Кренцин М.Д.");
        assertThat(saved.displayName()).isEqualTo("Кренцин");
    }

    @Test
    void shortNameOfAnotherTeacherIsAConflict() {
        assertThatThrownBy(() -> service.update(krentsin.getId(), request("Кренцін", null, null, "коваль о", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Викладач «Коваль О.» уже існує.");
        assertThat(krentsin.getShortName()).isEqualTo("Кренцін");
    }

    @Test
    void shortNameThatIsAnotherTeachersAliasIsAConflict() {
        given(aliases.findByNormalized(CatalogText.key("Ковальов О.")))
                .willReturn(Optional.of(new TeacherAlias(koval.getId(), "Ковальов О.", CatalogText.key("Ковальов О."))));

        assertThatThrownBy(() -> service.update(krentsin.getId(), request("Ковальов", "Олег", null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже записане як псевдонім іншого викладача");
    }

    @Test
    void ownAliasMayBecomeTheShortName() {
        given(aliases.findByNormalized(CatalogText.key("Кренцін М.")))
                .willReturn(Optional.of(new TeacherAlias(krentsin.getId(), "Кренцін М.", CatalogText.key("Кренцін М."))));

        assertThat(service.update(krentsin.getId(), request("Кренцін", null, null, "Кренцін М.", null)).shortName())
                .isEqualTo("Кренцін М.");
    }

    @Test
    void shortNameWithoutLettersIsAFieldError() {
        assertThatThrownBy(() -> service.update(krentsin.getId(), request("Кренцін", null, null, "...", null)))
                .isInstanceOf(FieldValidationException.class);
    }

    private static TeacherRequest request(String lastName, String firstName, String middleName, String shortName,
                                          String displayName) {
        return new TeacherRequest(lastName, firstName, middleName, shortName, displayName, null, null, null, null,
                null, null);
    }
}
