package ua.vtc.rozklad.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DOMAIN §10 normalization with real spellings from the college Excel and orders. */
class CatalogTextTest {

    @Test
    void latinLookAlikesInsideCyrillicWordsBecomeCyrillic() {
        // «Кренцiн» з латинською i та «Hепийвода» з латинською H
        assertThat(CatalogText.normalize("Кренцiн М.Д.")).isEqualTo("кренцін м.д.");
        assertThat(CatalogText.normalize("Hепийвода")).isEqualTo("непийвода");
    }

    @Test
    void latinWordsStayLatin() {
        assertThat(CatalogText.normalize("Java  Core")).isEqualTo("java core");
    }

    @Test
    void apostrophesAndYoAreUnified() {
        assertThat(CatalogText.normalize("Комп’ютерна")).isEqualTo(CatalogText.normalize("Комп'ютерна"))
                .isEqualTo(CatalogText.normalize("Компʼютерна"))
                .isEqualTo("компютерна");
        assertThat(CatalogText.normalize("Ёлка")).isEqualTo("елка");
    }

    @ParameterizedTest(name = "«{0}» = «{1}»")
    @CsvSource({
            "Укр.мова., Укр.мова",
            "Укр. мова, укрмова",
            "Фк, ФК",
            "Вс.Іст., Вс.іст.",
            "Вст.спц, Вст.спц.",
            "'Кренцін М. Д.', 'Кренцін М.Д.'",
            "С.З., с.з."
    })
    void keysIgnoreCaseDotsAndSpaces(String one, String other) {
        assertThat(CatalogText.key(one)).isEqualTo(CatalogText.key(other)).isNotEmpty();
    }

    @Test
    void differentPeopleKeepDifferentKeys() {
        // «Поліщук Л.» і «Поліщук М.» — різні люди (DOMAIN §10)
        assertThat(CatalogText.key("Поліщук Л.")).isNotEqualTo(CatalogText.key("Поліщук М."));
        assertThat(CatalogText.key("Овчар Ін.")).isNotEqualTo(CatalogText.key("Овчар Ів."));
    }

    @Test
    void englishLayoutIsConvertedToUkrainian() {
        assertThat(CatalogText.fromEnglishLayout("rhtywsy")).isEqualTo("кренцін");
        assertThat(CatalogText.fromEnglishLayout("Ytgbqdjlf")).isEqualTo("непийвода");
    }

    @Test
    void cleanCollapsesWhitespaceButKeepsLetters() {
        assertThat(CatalogText.clean("  Кренцін   М.Д. ")).isEqualTo("Кренцін М.Д.");
        assertThat(CatalogText.clean(null)).isNull();
    }
}
