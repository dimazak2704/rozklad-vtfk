package ua.vtc.rozklad.notification.text;

/** Ukrainian plural forms: 1 пара, 2 пари, 5 пар, 11 пар, 21 пара. */
public final class Plurals {

    private Plurals() {
    }

    public static String of(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = Math.abs(count) % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }
        if (last == 1) {
            return one;
        }
        return last >= 2 && last <= 4 ? few : many;
    }
}
