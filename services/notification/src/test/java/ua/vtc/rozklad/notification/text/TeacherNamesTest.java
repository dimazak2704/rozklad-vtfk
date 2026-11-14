package ua.vtc.rozklad.notification.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeacherNamesTest {

    @Test
    void shortFormBecomesSurnameWithSpacedInitials() {
        assertThat(TeacherNames.initials("Кренцін М.Д.")).isEqualTo("Кренцін М. Д.");
        assertThat(TeacherNames.initials("  Непийвода   М. В. ")).isEqualTo("Непийвода М. В.");
        assertThat(TeacherNames.initials("Крисак Л.")).isEqualTo("Крисак Л.");
    }

    @Test
    void twoLetterInitialsOfNamesakesAreKept() {
        assertThat(TeacherNames.initials("Овчар Ін.М.")).isEqualTo("Овчар Ін. М.");
        assertThat(TeacherNames.initials("Овчар Ів.М.")).isEqualTo("Овчар Ів. М.");
    }

    @Test
    void initialsGluedToTheSurnameAreSeparated() {
        assertThat(TeacherNames.initials("ХарченкоГ.")).isEqualTo("Харченко Г.");
    }

    @Test
    void publicFullNameCompletesMissingInitials() {
        assertThat(TeacherNames.initials("Алексєєнко", "Алексєєнко Олександра Анатоліївна", "Олександра"))
                .isEqualTo("Алексєєнко О. А.");
        assertThat(TeacherNames.initials("Колесник А.", "Колесник Анатолій Іванович", "Анатолій"))
                .isEqualTo("Колесник А. І.");
        // no patronymic, or the first name is not in the full name: the first initial only
        assertThat(TeacherNames.initials("Коваль", "Коваль Олена", "Олена")).isEqualTo("Коваль О.");
        assertThat(TeacherNames.initials("Коваль", "Коваль Олена Петрівна", "Оксана")).isEqualTo("Коваль О.");
    }

    @Test
    void shortFormWinsWhenItIsAsFullOrDisambiguatesNamesakes() {
        assertThat(TeacherNames.initials("Кренцін М.Д.", "Кренцін Михайло Дмитрович", "Михайло"))
                .isEqualTo("Кренцін М. Д.");
        assertThat(TeacherNames.initials("Овчар Ін.М.", "Овчар Інна Миколаївна", "Інна")).isEqualTo("Овчар Ін. М.");
        // the setting is off: no names, the short form as it is
        assertThat(TeacherNames.initials("Алексєєнко", null, null)).isEqualTo("Алексєєнко");
    }

    @Test
    void withoutInitialsTheNameStaysAsItIs() {
        assertThat(TeacherNames.initials("Коваль")).isEqualTo("Коваль");
        assertThat(TeacherNames.initials("Ковальчук-Мельник")).isEqualTo("Ковальчук-Мельник");
        assertThat(TeacherNames.initials(null)).isNull();
    }
}
