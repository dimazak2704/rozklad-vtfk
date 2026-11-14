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
    void withoutInitialsTheNameStaysAsItIs() {
        assertThat(TeacherNames.initials("Коваль")).isEqualTo("Коваль");
        assertThat(TeacherNames.initials("Ковальчук-Мельник")).isEqualTo("Ковальчук-Мельник");
        assertThat(TeacherNames.initials(null)).isNull();
    }
}
