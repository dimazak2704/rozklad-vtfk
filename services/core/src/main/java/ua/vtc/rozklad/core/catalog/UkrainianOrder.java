package ua.vtc.rozklad.core.catalog;

import java.text.Collator;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display order for names and codes: Ukrainian alphabet (А Б В Г Ґ … І Ї Й …, unlike code-point
 * order where «І» comes before «А») and numbers compared by value («ауд. 6» before «ауд. 12»,
 * «1КІ2» before «1КІ10»).
 */
public final class UkrainianOrder {

    /** Natural, Ukrainian-aware comparator of display strings; {@code null} goes last. */
    public static final Comparator<String> NATURAL = Comparator.nullsLast(UkrainianOrder::compare);

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");
    // compare() у RuleBasedCollator синхронізований, тож один екземпляр безпечний між потоками
    private static final Collator COLLATOR = collator();

    private UkrainianOrder() {
    }

    private static Collator collator() {
        Collator collator = Collator.getInstance(CatalogText.UKRAINIAN);
        collator.setStrength(Collator.SECONDARY);
        return collator;
    }

    private static int compare(String left, String right) {
        Collator collator = COLLATOR;
        Matcher a = CHUNK.matcher(left);
        Matcher b = CHUNK.matcher(right);
        while (a.find() && b.find()) {
            String chunkA = a.group();
            String chunkB = b.group();
            boolean digitsA = Character.isDigit(chunkA.charAt(0));
            boolean digitsB = Character.isDigit(chunkB.charAt(0));
            int result = digitsA && digitsB
                    ? compareNumbers(chunkA, chunkB)
                    : collator.compare(chunkA, chunkB);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private static int compareNumbers(String a, String b) {
        String left = a.replaceFirst("^0+(?=\\d)", "");
        String right = b.replaceFirst("^0+(?=\\d)", "");
        return left.length() != right.length() ? Integer.compare(left.length(), right.length()) : left.compareTo(right);
    }
}
