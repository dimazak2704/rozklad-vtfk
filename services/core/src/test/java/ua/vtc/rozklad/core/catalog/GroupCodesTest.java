package ua.vtc.rozklad.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Canonical group codes of DOMAIN §10: {@code ^\d[А-ЯІЇЄҐ]{1,3}\d?$}. */
class GroupCodesTest {

    @ParameterizedTest(name = "«{0}» → {1}")
    @CsvSource({
            "4КІ1, 4КІ1",
            "4ki1, 4КІ1",     // латиниця замість кирилиці
            "ІКІ1, 1КІ1",     // кирилична І замість цифри 1 (так в Excel)
            "1ТК 1, 1ТК1",    // пробіл усередині
            "4КИ1, 4КІ1",     // російська «И» замість «І»
            "4вe, 4ВЕ",       // без номера, латинська «e»
            "' 2 об 3 ', 2ОБ3",
            "1-тк-1, 1ТК1"
    })
    void canonicalForm(String raw, String canonical) {
        assertThat(GroupCodes.canonical(raw)).contains(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "КІ", "4КІЇЄ1", "кренцін", "4КІ12", "4 2 1"})
    void textThatIsNotAGroupCode(String raw) {
        assertThat(GroupCodes.canonical(raw)).isEmpty();
    }

    @Test
    void searchVariantsIncludePhoneticTransliteration() {
        // «2ob» людина пише, маючи на увазі «2ОБ»: візуально «o» → «о», фонетично «b» → «б»
        assertThat(GroupCodes.variants("2ob")).contains("2ОБ");
    }

    @Test
    void parseSplitsCourseLettersAndNumber() {
        assertThat(GroupCodes.parse("4КІ1")).contains(new GroupCodes.GroupCode(4, "КІ", 1));
        assertThat(GroupCodes.parse("4ВЕ")).contains(new GroupCodes.GroupCode(4, "ВЕ", null));
        assertThat(GroupCodes.parse("4ki1")).as("parse приймає лише канонічну форму").isEmpty();
    }

    @Test
    void specialtyLettersAreNormalizedLikeCodes() {
        assertThat(GroupCodes.specialtyLetters("ki")).contains("КІ");
        assertThat(GroupCodes.specialtyLetters(" К І ")).contains("КІ");
        assertThat(GroupCodes.specialtyLetters("КИ")).contains("КІ");
        assertThat(GroupCodes.specialtyLetters("КІЇЄ")).isEmpty();
        assertThat(GroupCodes.specialtyLetters("12")).isEmpty();
    }
}
