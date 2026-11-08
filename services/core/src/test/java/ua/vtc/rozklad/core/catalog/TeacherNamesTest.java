package ua.vtc.rozklad.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Short, full and sorted teacher names as they appear in the college files. */
class TeacherNamesTest {

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource({
            "Кренцін М.Д., Кренцін",
            "Крисак Л., Крисак",
            "Овчар Ін., Овчар",          // ініціал з двох літер
            "ХарченкоГ., Харченко",       // без пробілу перед ініціалом
            "Непийвода, Непийвода",
            "'Поліщук  М. В.', Поліщук"
    })
    void lastNameFromShortForm(String shortName, String lastName) {
        assertThat(TeacherNames.lastNameOf(shortName)).isEqualTo(lastName);
    }

    @Test
    void shortAndFullNamesFromParts() {
        assertThat(TeacherNames.shortName("Кренцін", "микола", "дмитрович")).isEqualTo("Кренцін М.Д.");
        assertThat(TeacherNames.shortName("Кренцін", null, null)).isEqualTo("Кренцін");
        assertThat(TeacherNames.fullName("Кренцін", "Микола", "Дмитрович", "Кренцін М.Д."))
                .isEqualTo("Кренцін Микола Дмитрович");
        assertThat(TeacherNames.fullName("Кренцін", null, null, "Кренцін М.Д.")).isEqualTo("Кренцін М.Д.");
    }

    @Test
    void ukrainianOrderPutsІAfterИAndNumbersByValue() {
        List<String> sorted = Stream.of("Іванов", "Абрамчук", "Їжак", "Гнатюк", "Ґудзь", "Иванчук")
                .sorted(UkrainianOrder.NATURAL).toList();
        assertThat(sorted).containsExactly("Абрамчук", "Гнатюк", "Ґудзь", "Иванчук", "Іванов", "Їжак");

        assertThat(Stream.of("ауд. 12", "ауд. 6", "1КІ10", "1КІ2").sorted(UkrainianOrder.NATURAL).toList())
                .containsExactly("1КІ2", "1КІ10", "ауд. 6", "ауд. 12");
    }
}
