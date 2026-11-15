package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.internal.MailRecipientService.MailRecipientDto;
import ua.vtc.rozklad.core.shared.security.Roles;

/** Who an e-mail reaches: the teacher's account, an account, every admin; facts from auth, no filtering. */
class MailRecipientServiceTest {

    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final FakeIdentityProvider identity = new FakeIdentityProvider();
    private final MailRecipientService service = new MailRecipientService(identity, teachers);

    private Teacher krentsin;
    private UUID krentsinAccount;

    @BeforeEach
    void setUp() {
        krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        krentsinAccount = identity.grantedByEmail("krentsin@vtc.vn.ua", Set.of(Roles.TEACHER));
        identity.linkGoogle(krentsinAccount);
        krentsin.setAccountId(krentsinAccount);
        given(teachers.findById(any())).willReturn(Optional.empty());
        given(teachers.findById(krentsin.getId())).willReturn(Optional.of(krentsin));
        given(teachers.findByAccountId(any())).willReturn(Optional.empty());
        given(teachers.findByAccountId(krentsinAccount)).willReturn(Optional.of(krentsin));
        given(teachers.findAllByAccountIdIn(anyCollection())).willReturn(List.of());
    }

    @Test
    void theTeachersLinkedAccountWithItsSignInAddress() {
        assertThat(service.forTeacher(krentsin.getId())).containsExactly(new MailRecipientDto(krentsinAccount,
                krentsin.getId(), "krentsin@vtc.vn.ua", null, "krentsin@vtc.vn.ua", true, List.of(Roles.TEACHER),
                true, false, false));
    }

    @Test
    void aTeacherWithoutAccountOrUnknownGivesNobody() {
        Teacher withoutAccount = new Teacher("Непийвода", "Непийвода М.В.", "Непийвода");
        given(teachers.findById(withoutAccount.getId())).willReturn(Optional.of(withoutAccount));

        assertThat(service.forTeacher(withoutAccount.getId())).isEmpty();
        assertThat(service.forTeacher(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aLinkThatAuthLostGivesNobody() {
        identity.deleteUser(krentsinAccount);

        assertThat(service.forTeacher(krentsin.getId())).isEmpty();
    }

    @Test
    void anInvitedAccountHasNoAddress() {
        UUID invited = identity.existingUser("nepyivoda", "Teacher-Password-2026", true);

        assertThat(service.forAccount(invited)).singleElement().satisfies(recipient -> {
            assertThat(recipient.email()).isNull();
            assertThat(recipient.username()).isEqualTo("nepyivoda");
            assertThat(recipient.teacherId()).isNull();
            assertThat(recipient.googleLinked()).isFalse();
        });
    }

    @Test
    void anAccountCarriesItsTeacher() {
        assertThat(service.forAccount(krentsinAccount)).extracting(MailRecipientDto::teacherId)
                .containsExactly(krentsin.getId());
        assertThat(service.forAccount(UUID.randomUUID())).isEmpty();
    }

    @Test
    void everyAdminWithStateButNoTeachers() {
        UUID dispatcher = identity.grantedByEmail("dispatcher@vtc.vn.ua", Set.of(Roles.ADMIN));
        UUID spare = identity.usableAdmin("spare");
        UUID teachingAdmin = identity.grantedByEmail("teaching.admin@vtc.vn.ua", Set.of(Roles.ADMIN, Roles.TEACHER));
        Teacher teacher = new Teacher("Адмінко", "Адмінко А.А.", "Адмінко");
        teacher.setAccountId(teachingAdmin);
        given(teachers.findAllByAccountIdIn(anyCollection())).willReturn(List.of(teacher));
        identity.revoke(dispatcher);

        List<MailRecipientDto> admins = service.admins();

        assertThat(admins).extracting(MailRecipientDto::accountId)
                .containsExactlyInAnyOrder(dispatcher, spare, teachingAdmin)
                .doesNotContain(krentsinAccount);
        assertThat(admins).filteredOn(admin -> admin.accountId().equals(dispatcher)).singleElement()
                .satisfies(admin -> {
                    assertThat(admin.enabled()).isFalse();
                    assertThat(admin.revoked()).isTrue();
                });
        assertThat(admins).filteredOn(admin -> admin.accountId().equals(teachingAdmin)).singleElement()
                .satisfies(admin -> {
                    assertThat(admin.teacherId()).isEqualTo(teacher.getId());
                    assertThat(admin.roles()).containsExactly(Roles.ADMIN, Roles.TEACHER);
                });
    }

    @Test
    void authDownIs503() {
        identity.goDown();

        assertThatThrownBy(() -> service.forTeacher(krentsin.getId()))
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        problem -> assertThat(problem.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(service::admins).isInstanceOf(ErrorResponseException.class);
    }
}
