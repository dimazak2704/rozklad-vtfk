package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.Invite;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.InviteStatus;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService.TeacherAccountDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;

/** Linking a teacher to an existing Keycloak account (the demo accounts of the realm import). */
class TeacherAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final InviteRepository invites = mock(InviteRepository.class);
    private final FakeIdentityProvider keycloak = new FakeIdentityProvider();
    private final TeacherAccountService service = new TeacherAccountService(teachers, invites, keycloak,
            Clock.fixed(NOW, ZoneId.of("Europe/Kyiv")));

    private Teacher krentsin;
    private UUID account;

    @BeforeEach
    void setUp() {
        krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(teachers.findById(krentsin.getId())).willReturn(Optional.of(krentsin));
        given(teachers.findByKeycloakUserId(any())).willReturn(Optional.empty());
        given(invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(any())).willReturn(List.of());
        account = keycloak.existingUser("krentsin", "teacher", true);
    }

    @Test
    void linksAnExistingAccountGrantsTeacherAndRevokesOpenInvites() {
        Invite open = new Invite(krentsin.getId(), "a".repeat(64), NOW.plusSeconds(3600), "admin");
        given(invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(krentsin.getId())).willReturn(List.of(open));

        TeacherAccountDto linked = service.link(krentsin.getId(), "  KRENTSIN ", "admin");

        assertThat(linked).isEqualTo(new TeacherAccountDto(krentsin.getId(), "Кренцін М.Д.", true, account,
                "krentsin", true));
        assertThat(krentsin.getKeycloakUserId()).isEqualTo(account);
        assertThat(keycloak.user(account).orElseThrow().roles()).contains(Roles.TEACHER);
        assertThat(open.status(NOW)).isEqualTo(InviteStatus.REVOKED);
        assertThat(service.get(krentsin.getId()).username()).isEqualTo("krentsin");
    }

    @Test
    void linkingTheSameAccountAgainChangesNothing() {
        krentsin.setKeycloakUserId(account);

        assertThat(service.link(krentsin.getId(), "krentsin", "admin").userId()).isEqualTo(account);
        assertThat(keycloak.user(account).orElseThrow().roles()).isEmpty();
    }

    @Test
    void unknownOrDisabledAccountIs422() {
        keycloak.existingUser("blocked", "secret", false);

        assertThatThrownBy(() -> service.link(krentsin.getId(), "nobody", "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThatThrownBy(() -> service.link(krentsin.getId(), "blocked", "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("ACCOUNT_DISABLED"));
        assertThat(krentsin.getKeycloakUserId()).isNull();
    }

    @Test
    void accountOfAnotherTeacherIs409() {
        Teacher owner = new Teacher("Непийвода", "Непийвода М.В.", "Непийвода");
        given(teachers.findByKeycloakUserId(account)).willReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.link(krentsin.getId(), "krentsin", "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Обліковий запис «krentsin» уже прив'язано до викладача Непийвода М.В..");
    }

    @Test
    void anotherLiveAccountMustBeUnlinkedFirstButALostOneIsReplaced() {
        UUID other = keycloak.existingUser("mkrentsin", "secret", true);
        krentsin.setKeycloakUserId(other);

        assertThatThrownBy(() -> service.link(krentsin.getId(), "krentsin", "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Викладач Кренцін М.Д. уже має обліковий запис «mkrentsin». Спершу відв'яжіть його.");

        keycloak.deleteUser(other);
        assertThat(service.link(krentsin.getId(), "krentsin", "admin").userId()).isEqualTo(account);
    }

    @Test
    void unlinkIsIdempotentAndKeepsTheUser() {
        krentsin.setKeycloakUserId(account);

        service.unlink(krentsin.getId(), "admin");
        service.unlink(krentsin.getId(), "admin");

        assertThat(krentsin.getKeycloakUserId()).isNull();
        assertThat(keycloak.user(account)).isPresent();
        assertThat(service.get(krentsin.getId()).linked()).isFalse();
    }

    @Test
    void unknownTeacherIs404AndKeycloakOutageIs503() {
        assertThatThrownBy(() -> service.link(UUID.randomUUID(), "krentsin", "admin"))
                .isInstanceOf(NotFoundException.class);

        keycloak.goDown();
        assertThatThrownBy(() -> service.link(krentsin.getId(), "krentsin", "admin"))
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        failed -> assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
