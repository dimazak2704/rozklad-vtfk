package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;

class HoursPerPairTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final SettingRepository settings = mock(SettingRepository.class);
    private final HoursPerPair hoursPerPair = new HoursPerPair(settings);

    @Test
    void withoutTheSettingAPairIsTwoAcademicHours() {
        given(settings.findById(HoursPerPair.KEY)).willReturn(Optional.empty());

        assertThat(hoursPerPair.current()).isEqualByComparingTo("2");
    }

    @Test
    void settingMayBeANumberOrANumericString() {
        given(settings.findById(HoursPerPair.KEY)).willReturn(Optional.of(setting("1")));
        assertThat(hoursPerPair.current()).isEqualByComparingTo(BigDecimal.ONE);

        given(settings.findById(HoursPerPair.KEY)).willReturn(Optional.of(setting("\"1.5\"")));
        assertThat(hoursPerPair.current()).isEqualByComparingTo("1.5");
    }

    @Test
    void unusableValueFallsBackToTheDomainDefault() {
        given(settings.findById(HoursPerPair.KEY)).willReturn(Optional.of(setting("\"дві\"")));
        assertThat(hoursPerPair.current()).isEqualByComparingTo("2");

        given(settings.findById(HoursPerPair.KEY)).willReturn(Optional.of(setting("0")));
        assertThat(hoursPerPair.current()).isEqualByComparingTo("2");
    }

    private Setting setting(String value) {
        return new Setting(HoursPerPair.KEY, json.readTree(value), "admin");
    }
}
