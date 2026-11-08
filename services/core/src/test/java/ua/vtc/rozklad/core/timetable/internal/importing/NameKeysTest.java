package ua.vtc.rozklad.core.timetable.internal.importing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.catalog.CatalogText;

/** The import matches names by the catalog's keys (DOMAIN §10), so spellings from Excel find catalog rows. */
class NameKeysTest {

    @Test
    void keyDropsDotsSpacesAndApostrophes() {
        assertThat(CatalogText.key("Укр.мова.")).isEqualTo("укрмова");
        assertThat(CatalogText.key("Укр. мова")).isEqualTo("укрмова");
        assertThat(CatalogText.key("Вс.Іст.")).isEqualTo(CatalogText.key("Вс.іст."));
        assertThat(CatalogText.key("Компʼютерні мережі")).isEqualTo(CatalogText.key("Комп’ютерні мережі"));
    }

    @Test
    void latinLookAlikesBecomeCyrillicInCyrillicNames() {
        // «K», «e» і «M» тут латинські, як буває після копіювання з Excel
        assertThat(CatalogText.key("Kрeнцін M.Д.")).isEqualTo("кренцінмд").isEqualTo(CatalogText.key("Кренцін М.Д."));
        assertThat(CatalogText.normalize("Java Spring")).isEqualTo("java spring");
    }

    @Test
    void groupCodesAreCanonical() {
        assertThat(CatalogNameIndex.groupKey("ІКІ1")).isEqualTo("1КІ1");
        assertThat(CatalogNameIndex.groupKey("1ТК 1")).isEqualTo("1ТК1");
        assertThat(CatalogNameIndex.groupKey("4КИ1")).isEqualTo("4КІ1");
        assertThat(CatalogNameIndex.groupKey("4ВЕ")).isEqualTo("4ВЕ");
    }
}
