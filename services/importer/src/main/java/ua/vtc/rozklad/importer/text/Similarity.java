package ua.vtc.rozklad.importer.text;

import java.util.List;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * String similarity used by normalization: Jaro-Winkler (DOMAIN §10: ≥ 0.92 for surnames),
 * optimal string alignment distance (typos: one substitution, insertion, deletion or swap) and a
 * guard against merging masculine and feminine forms of one surname («Шидловський» ≠ «Шидловська»).
 */
public final class Similarity {

    public static final double SURNAME_THRESHOLD = 0.92;

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    // пари закінчень чоловічої/жіночої форм прізвищ
    private static final List<String[]> GENDER_SUFFIXES = List.of(
            new String[] {"ський", "ська"}, new String[] {"цький", "цька"}, new String[] {"зький", "зька"},
            new String[] {"ий", "а"}, new String[] {"ій", "я"}, new String[] {"ов", "ова"},
            new String[] {"ев", "ева"}, new String[] {"єв", "єва"}, new String[] {"ін", "іна"},
            new String[] {"їн", "їна"}, new String[] {"ин", "ина"});

    private Similarity() {
    }

    public static double jaroWinkler(String a, String b) {
        return JARO_WINKLER.apply(a, b);
    }

    /** Optimal string alignment (restricted Damerau-Levenshtein) distance. */
    public static int osaDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Masculine and feminine forms of one surname stem — different people, never merged. */
    public static boolean genderPair(String a, String b) {
        for (String[] pair : GENDER_SUFFIXES) {
            if (sameStem(a, b, pair[0], pair[1]) || sameStem(a, b, pair[1], pair[0])) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameStem(String a, String b, String suffixA, String suffixB) {
        return a.endsWith(suffixA) && b.endsWith(suffixB)
                && a.substring(0, a.length() - suffixA.length()).equals(b.substring(0, b.length() - suffixB.length()));
    }

    /** The two keys differ in a digit («ФОЕМ1» vs «ФОЕМ 2») — distinct entities. */
    public static boolean differInDigits(String a, String b) {
        return !digits(a).equals(digits(b));
    }

    private static String digits(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                result.append(text.charAt(i));
            }
        }
        return result.toString();
    }

    /**
     * A likely typo of a frequent spelling: distance 1 on keys of at least 5 characters, not a
     * gender pair, digits equal. Used only together with frequency rules by the parsers.
     */
    public static boolean likelyTypo(String rareKey, String frequentKey) {
        return rareKey.length() >= 5 && frequentKey.length() >= 5
                && !rareKey.equals(frequentKey)
                && Math.abs(rareKey.length() - frequentKey.length()) <= 1
                && osaDistance(rareKey, frequentKey) == 1
                && !genderPair(rareKey, frequentKey)
                && !differInDigits(rareKey, frequentKey);
    }
}
