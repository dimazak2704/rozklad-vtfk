package ua.vtc.rozklad.core.timetable.internal.importing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameKeysTest {

    @Test
    void normalizesCaseSpacesAndApostrophes() {
        assertThat(NameKeys.normalize("  Компʼютерні   мережі ")).isEqualTo("комп'ютерні мережі");
        assertThat(NameKeys.normalize("Комп’ютерні мережі")).isEqualTo("комп'ютерні мережі");
    }

    @Test
    void keyDropsDotsAndSpacesOfAbbreviations() {
        assertThat(NameKeys.key("Укр.мова.")).isEqualTo("укрмова");
        assertThat(NameKeys.key("Укр. мова")).isEqualTo("укрмова");
        assertThat(NameKeys.key("Вс.Іст.")).isEqualTo(NameKeys.key("Вс.іст."));
    }

    @Test
    void latinLookAlikesBecomeCyrillicInCyrillicNames() {
        // «K», «e» і «M» тут латинські, як буває після копіювання з Excel
        assertThat(NameKeys.key("Kрeнцін M.Д.")).isEqualTo("кренцінмд").isEqualTo(NameKeys.key("Кренцін М.Д."));
        assertThat(NameKeys.normalize("Java Spring")).isEqualTo("java spring");
    }

    @Test
    void mostlyLatinWordIsNotConverted() {
        assertThat(NameKeys.normalize("Kpeнцiн")).isEqualTo("kpeнцiн");
    }

    @Test
    void groupCodesAreCanonical() {
        assertThat(NameKeys.groupKey("ІКІ1")).isEqualTo("1КІ1");
        assertThat(NameKeys.groupKey("1ТК 1")).isEqualTo("1ТК1");
        assertThat(NameKeys.groupKey("4КИ1")).isEqualTo("4КІ1");
        assertThat(NameKeys.groupKey("4ВЕ")).isEqualTo("4ВЕ");
    }
}
