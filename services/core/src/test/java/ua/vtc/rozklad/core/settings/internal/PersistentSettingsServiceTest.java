package ua.vtc.rozklad.core.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.core.settings.ChangeApprovalMode;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;
import ua.vtc.rozklad.core.settings.SettingsConstraint;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/** Typed settings with defaults (DOMAIN §7–§8). */
class PersistentSettingsServiceTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final SettingRepository repository = mock(SettingRepository.class);

    @Test
    void missingRowsGiveTheDocumentedDefaults() {
        given(repository.findAll()).willReturn(List.of());

        SystemSettings settings = service().current();

        assertThat(settings).isEqualTo(SystemSettings.DEFAULTS);
        assertThat(settings.approvalMode()).isEqualTo(ChangeApprovalMode.CONFIRM);
        assertThat(settings.confirmTimeoutMinutes()).isEqualTo(120);
        assertThat(settings.signatoryPosition()).isEqualTo("Заступник директора з НР");
        assertThat(settings.signatoryName()).isEqualTo("Микола ПОЛІЩУК");
        assertThat(settings.collegeName()).isEqualTo("Вінницький технічний фаховий коледж");
        assertThat(settings.hoursPerPair()).isEqualTo(2);
        assertThat(settings.showTeacherFullNames()).isFalse();
        assertThat(settings.requiresConfirmation()).isTrue();
    }

    @Test
    void storedValuesWin() {
        UUID profile = UUID.randomUUID();
        given(repository.findAll()).willReturn(List.of(
                new Setting("substitution.approvalMode", JSON.stringNode("AUTO"), "admin"),
                new Setting("accounting.hoursPerPair", JSON.numberNode(1), "admin"),
                new Setting("calendar.activeBellProfileId", JSON.stringNode(profile.toString()), "admin")));

        SystemSettings settings = service().current();

        assertThat(settings.approvalMode()).isEqualTo(ChangeApprovalMode.AUTO);
        assertThat(settings.hoursPerPair()).isEqualTo(1);
        assertThat(settings.activeBellProfileId()).isEqualTo(profile);
        assertThat(settings.collegeName()).isEqualTo(SystemSettings.DEFAULTS.collegeName());
    }

    @Test
    void corruptedValueFailsLoudlyInsteadOfFallingBackToTheDefault() {
        given(repository.findAll()).willReturn(List.of(
                new Setting("accounting.hoursPerPair", JSON.stringNode("two"), "admin")));

        assertThatThrownBy(() -> service().current())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.hoursPerPair");
    }

    @Test
    void updateStoresOnlyChangedValuesWithTheActor() {
        given(repository.findById(any())).willReturn(Optional.empty());
        given(repository.findById("substitution.approvalMode"))
                .willReturn(Optional.of(new Setting("substitution.approvalMode", JSON.stringNode("CONFIRM"), "system")));
        SystemSettings proposed = new SystemSettings(ChangeApprovalMode.CONFIRM, 60, "  Директор ", "Світлана ВАСИЛЮК",
                "ВТФК", 2, true, null);

        SystemSettings saved = service().update(proposed, "admin");

        assertThat(saved.signatoryPosition()).isEqualTo("Директор");
        ArgumentCaptor<Setting> stored = ArgumentCaptor.forClass(Setting.class);
        // 7 змінених значень + рядок версії налаштувань (його ще не було)
        verify(repository, org.mockito.Mockito.times(8)).save(stored.capture());
        assertThat(stored.getAllValues()).extracting(Setting::getKey)
                .doesNotContain("substitution.approvalMode")
                .contains("substitution.confirmTimeoutMinutes", "calendar.activeBellProfileId", "settings.version");
        assertThat(stored.getAllValues()).allSatisfy(setting -> assertThat(setting.getUpdatedBy()).isEqualTo("admin"));
    }

    @Test
    void updateOfAnOlderVersionIsRejected() {
        given(repository.findById(PersistentSettingsService.VERSION_KEY))
                .willReturn(Optional.of(new Setting(PersistentSettingsService.VERSION_KEY, JSON.numberNode(3L), "admin")));

        assertThatThrownBy(() -> service().update(SystemSettings.DEFAULTS, 2L, "admin"))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void invalidValuesAndConstraintsAreFieldErrors() {
        SettingsConstraint unknownProfile = (proposed, errors) -> errors.add("activeBellProfileId", "Профіль дзвінків не знайдено.");
        SystemSettings proposed = new SystemSettings(ChangeApprovalMode.AUTO, 1, " ", "Ім'я", "Коледж", 9, false,
                UUID.randomUUID());

        assertThatThrownBy(() -> new PersistentSettingsService(repository, List.of(unknownProfile)).update(proposed, "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field)
                        .containsExactly("activeBellProfileId", "confirmTimeoutMinutes", "hoursPerPair",
                                "signatoryPosition"));
        verify(repository, never()).save(any());
    }

    private PersistentSettingsService service() {
        return new PersistentSettingsService(repository, List.of());
    }
}
