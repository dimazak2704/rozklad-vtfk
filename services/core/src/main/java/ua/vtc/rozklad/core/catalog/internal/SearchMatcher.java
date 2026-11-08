package ua.vtc.rozklad.core.catalog.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.GroupCodes;

/**
 * Scores a candidate text against a search query, 0..100. The same tiers as {@code matchScore}
 * in {@code web/src/lib/search.ts}, so the global search and local filters agree:
 * <ol>
 *   <li>100 — the key equals the query key («4ki1» = «4КІ1» after transliteration);</li>
 *   <li>90 — the key starts with it («кренц» → «Кренцін М.Д.»);</li>
 *   <li>80 — a word starts with it; 75 — a numeric query equals a whole number word («6» finds
 *       «ауд. 6», not «16»);</li>
 *   <li>60 — the key contains it; 40 — a word head is within 1–2 typos (Damerau–Levenshtein).</li>
 * </ol>
 * Query variants cover a group code typed in Latin and text typed with the English layout.
 */
final class SearchMatcher {

    static final int MIN_SCORE = 40;
    static final int EXACT = 100;

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-_,()/]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LAYOUT_CHARS = Pattern.compile(".*[a-z;'\\[\\],.`].*");

    private SearchMatcher() {
    }

    /** Normalized variants of the query: as typed, as a group code, as the other keyboard layout. */
    static List<String> queryVariants(String query) {
        Set<String> variants = new LinkedHashSet<>();
        String normalized = CatalogText.normalize(query);
        if (!normalized.isEmpty()) {
            variants.add(normalized);
        }
        List<String> groupCodes = GroupCodes.variants(query);
        groupCodes.forEach(code -> variants.add(CatalogText.normalize(code)));
        if (!normalized.isEmpty() && !CatalogText.containsCyrillic(normalized)
                && LAYOUT_CHARS.matcher(normalized).matches() && groupCodes.isEmpty()) {
            variants.add(CatalogText.normalize(CatalogText.fromEnglishLayout(query)));
        }
        return List.copyOf(variants);
    }

    /** Best score of any variant against any candidate text (name, short name, aliases). */
    static int score(List<String> variants, List<String> candidates) {
        int best = 0;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String text = CatalogText.normalize(candidate);
            for (String variant : variants) {
                best = Math.max(best, scoreVariant(variant, text));
                if (best == EXACT) {
                    return best;
                }
            }
        }
        return best;
    }

    static int scoreVariant(String query, String text) {
        if (query.isEmpty() || text.isEmpty()) {
            return 0;
        }
        String textKey = SEPARATORS.matcher(text).replaceAll("");
        String queryKey = SEPARATORS.matcher(query).replaceAll("");
        if (queryKey.isEmpty()) {
            return 0;
        }
        if (textKey.equals(queryKey)) {
            return EXACT;
        }
        if (textKey.startsWith(queryKey)) {
            return 90;
        }
        List<String> words = Arrays.stream(SEPARATORS.split(text)).filter(word -> !word.isEmpty()).toList();
        if (words.stream().anyMatch(word -> word.startsWith(query))) {
            return 80;
        }
        if (DIGITS.matcher(query).matches()) {
            return words.contains(query) ? 75 : 0;
        }
        if (textKey.contains(queryKey)) {
            return 60;
        }
        if (queryKey.length() >= 4) {
            int limit = queryKey.length() >= 7 ? 2 : 1;
            for (String word : words) {
                String head = word.substring(0, Math.min(word.length(), queryKey.length()));
                if (editDistance(queryKey, head, limit) <= limit) {
                    return 40;
                }
            }
        }
        return 0;
    }

    /**
     * Damerau–Levenshtein distance (optimal string alignment) that stops early: anything above
     * {@code limit} is returned as {@code limit + 1}.
     */
    static int editDistance(String a, String b, int limit) {
        if (a.equals(b)) {
            return 0;
        }
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int rows = a.length() + 1;
        int cols = b.length() + 1;
        List<int[]> d = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            int[] row = new int[cols];
            row[0] = i;
            d.add(row);
        }
        for (int j = 0; j < cols; j++) {
            d.getFirst()[j] = j;
        }
        for (int i = 1; i < rows; i++) {
            int rowMin = Integer.MAX_VALUE;
            int[] row = d.get(i);
            int[] previous = d.get(i - 1);
            for (int j = 1; j < cols; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(previous[j] + 1, row[j - 1] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, d.get(i - 2)[j - 2] + 1);
                }
                row[j] = value;
                rowMin = Math.min(rowMin, value);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
        }
        return d.get(rows - 1)[cols - 1];
    }
}
