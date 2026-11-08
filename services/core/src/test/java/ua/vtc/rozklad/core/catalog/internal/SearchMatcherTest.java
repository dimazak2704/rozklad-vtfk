package ua.vtc.rozklad.core.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Scoring tiers of the global search (UI.md «wow» 3), the same as web/src/lib/search.ts. */
class SearchMatcherTest {

    @Test
    void latinGroupCodeIsAnExactMatch() {
        assertThat(score("4ki1", "4КІ1")).isEqualTo(100);
        assertThat(score("4 КІ 1", "4КІ1")).isEqualTo(100);
        assertThat(score("4КИ1", "4КІ1")).isEqualTo(100);
    }

    @Test
    void beginningOfASurnameIsAPrefixMatch() {
        assertThat(score("кренц", "Кренцін М.Д.")).isEqualTo(90);
        assertThat(score("Кренцін", "Кренцін М.Д.")).isEqualTo(90);
    }

    @Test
    void numericQueryMatchesWholeRoomNumberOnly() {
        assertThat(score("6", "6")).isEqualTo(100);
        assertThat(score("6", "ауд. 6")).isEqualTo(80);
        assertThat(score("6", "16")).isZero();
        assertThat(score("6", "4КІ6")).isZero();
    }

    @Test
    void typosAreToleratedForLongerQueries() {
        assertThat(score("кренцин", "Кренцін М.Д.")).isEqualTo(40);
        assertThat(score("непийвда", "Непийвода М.В.")).isEqualTo(40);
        assertThat(score("кре", "Кравчук")).isZero();
    }

    @Test
    void englishKeyboardLayoutIsUnderstood() {
        assertThat(SearchMatcher.queryVariants("rhtyw")).contains("кренц");
        assertThat(score("rhtyw", "Кренцін М.Д.")).isEqualTo(90);
    }

    @Test
    void containsAndWordStartAreBetweenPrefixAndFuzzy() {
        assertThat(score("мова", "Укр. мова")).isEqualTo(80);
        assertThat(score("мова", "Укрмова")).isEqualTo(60);
    }

    @Test
    void separatorsOnlyQueryMatchesNothing() {
        assertThat(SearchMatcher.score(SearchMatcher.queryVariants("..."), List.of("6"))).isZero();
    }

    @Test
    void editDistanceStopsAboveTheLimit() {
        assertThat(SearchMatcher.editDistance("кренцин", "кренцін", 2)).isEqualTo(1);
        assertThat(SearchMatcher.editDistance("ab", "ba", 1)).isEqualTo(1);
        assertThat(SearchMatcher.editDistance("abcdef", "uvwxyz", 2)).isEqualTo(3);
    }

    private static int score(String query, String candidate) {
        return SearchMatcher.score(SearchMatcher.queryVariants(query), List.of(candidate));
    }
}
