package ua.vtc.rozklad.importer.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.contracts.RoomType;

/** DOMAIN §10 normalization rules on the real typos of the college files. */
class TextRulesTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "ІКІ1, 1КІ1", "'1ТК 1', 1ТК1", "4КИ1, 4КІ1", "4KI1, 4КІ1", "'1ЕА1 ', 1ЕА1", "1ВЕ, 1ВЕ", "'3ЕП        ', 3ЕП",
            "4ки1, 4КІ1"})
    void groupCodesAreCanonical(String raw, String canonical) {
        assertThat(GroupCode.parse(raw)).get().extracting(GroupCode::code).isEqualTo(canonical);
    }

    @Test
    void groupCodeParts() {
        GroupCode code = GroupCode.parse("4КІ1").orElseThrow();
        assertThat(code.course()).isEqualTo(4);
        assertThat(code.letters()).isEqualTo("КІ");
        assertThat(code.number()).isEqualTo(1);
        assertThat(GroupCode.parse("1ВЕ").orElseThrow().number()).isNull();
        assertThat(GroupCode.parse("ФК")).isEmpty();
        assertThat(GroupCode.parse("Ін.мова")).isEmpty();
        assertThat(GroupCode.parse("9КІ1")).isEmpty();
    }

    @Test
    void keysUnifyApostrophesCaseAndLatinLookalikes() {
        assertThat(TextNormalizer.key("Остап’юк")).isEqualTo(TextNormalizer.key("ОСТАП'ЮК"));
        // латинські «a», «o», «c» у кириличному слові
        assertThat(TextNormalizer.key("Mатемaтикa")).isEqualTo("математика");
        assertThat(TextNormalizer.key("Project")).isEqualTo("project");
        assertThat(TextNormalizer.abbreviationKey("Укр.мова.")).isEqualTo(TextNormalizer.abbreviationKey("Укр. мова"));
        assertThat(TextNormalizer.abbreviationKey("Фк")).isEqualTo(TextNormalizer.abbreviationKey("ФК"));
        assertThat(TextNormalizer.clean("  Ткач  Ж. ")).isEqualTo("Ткач Ж.");
    }

    @Test
    void personNamesFromTheExcel() {
        assertThat(PersonName.parse("ХарченкоГ.")).satisfies(name -> {
            assertThat(name.surname()).isEqualTo("Харченко");
            assertThat(name.initials()).containsExactly("Г");
        });
        assertThat(PersonName.parse("Крисак Л .(гр).")).satisfies(name -> {
            assertThat(name.shortForm()).isEqualTo("Крисак Л.");
            assertThat(name.wholeGroupMarker()).isTrue();
        });
        assertThat(PersonName.parse("Харченко О.(п)")).satisfies(name -> {
            assertThat(name.shortForm()).isEqualTo("Харченко О.");
            assertThat(name.subgroupMarker()).isTrue();
        });
        assertThat(PersonName.parse("Граболінська  гр.").shortForm()).isEqualTo("Граболінська");
        assertThat(PersonName.parse("Овчар Ін.М.").initials()).containsExactly("Ін", "М");
        assertThat(PersonName.parse("   ")).isNull();
    }

    @Test
    void initialsCompatibility() {
        PersonName full = PersonName.parse("Овчар Ін.М.");
        assertThat(full.initialsCompatibleWith(PersonName.parse("Овчар Ін."))).isTrue();
        assertThat(full.initialsCompatibleWith(PersonName.parse("Овчар"))).isTrue();
        assertThat(full.initialsCompatibleWith(PersonName.parse("Овчар Ів."))).isFalse();
        assertThat(PersonName.parse("Поліщук Л.").initialsCompatibleWith(PersonName.parse("Поліщук М."))).isFalse();
    }

    @Test
    void typoRulesKeepDifferentPeopleApart() {
        assertThat(Similarity.likelyTypo("граболінська", "гарболінська")).isTrue();
        assertThat(Similarity.likelyTypo("герасменко", "герасименко")).isTrue();
        assertThat(Similarity.likelyTypo("хорченко", "харченко")).isTrue();
        assertThat(Similarity.likelyTypo("шидловський", "шидловська")).isFalse();
        assertThat(Similarity.likelyTypo("ткачук", "ткач")).isFalse();
        assertThat(Similarity.likelyTypo("фоем1", "фоем2")).isFalse();
        assertThat(Similarity.genderPair("поповський", "поповська")).isTrue();
        assertThat(Similarity.osaDistance("гарболінська", "граболінська")).isEqualTo(1);
        assertThat(Similarity.jaroWinkler("харченко", "хорченко")).isGreaterThan(Similarity.SURNAME_THRESHOLD);
    }

    @Test
    void roomNames() {
        assertThat(List.of("с.з", "С.З.", "с. з.", "с.з.")).allMatch(raw -> RoomNames.canonical(raw).equals(RoomNames.GYM));
        assertThat(RoomNames.canonical("с.п.")).isEqualTo(RoomNames.SPORTS_GROUND);
        assertThat(RoomNames.canonical("ауд. 307")).isEqualTo("307");
        assertThat(RoomNames.canonical("6.")).isEqualTo("6");
        assertThat(RoomNames.canonical(" ")).isEmpty();
        assertThat(RoomNames.type("с.з.")).isEqualTo(RoomType.GYM);
        assertThat(RoomNames.defaultMaxParallel("с.з.")).isEqualTo(3);
        assertThat(RoomNames.type("413")).isEqualTo(RoomType.CLASSROOM);
    }
}
