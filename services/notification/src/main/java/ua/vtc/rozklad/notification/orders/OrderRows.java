package ua.vtc.rozklad.notification.orders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;

/**
 * Picks the rows of an order that concern one group or one teacher. Rows carry text snapshots
 * (no ids), so teachers are matched by name: same surname and compatible initials — «Поліщук Л.»
 * and «Поліщук М.» are different people (DOMAIN §10), «Непийвода» matches «Непийвода М.В.».
 */
public final class OrderRows {

    private static final Locale UK = Locale.forLanguageTag("uk");

    private OrderRows() {
    }

    /** Changes and room changes of one group. */
    public static List<Item> forGroup(OrderPayload order, String groupCode) {
        String code = normalizeGroup(groupCode);
        return Stream.concat(order.items().stream(), order.roomChanges().stream())
                .filter(item -> code.equals(normalizeGroup(item.groupCode())))
                .toList();
    }

    /** Rows where the teacher is the replaced or the replacing one. */
    public static List<Item> forTeacher(OrderPayload order, String teacherName) {
        Name teacher = Name.parse(teacherName);
        if (teacher == null) {
            return List.of();
        }
        return Stream.concat(order.items().stream(), order.roomChanges().stream())
                .filter(item -> mentions(item.fromTeacher(), teacher) || mentions(item.toTeacher(), teacher))
                .toList();
    }

    static boolean mentions(String cell, Name teacher) {
        if (cell == null || cell.isBlank()) {
            return false;
        }
        return Arrays.stream(cell.split("[/\\n]"))
                .map(Name::parse)
                .anyMatch(name -> name != null && name.sameAs(teacher));
    }

    static String normalizeGroup(String code) {
        return code == null ? "" : code.replaceAll("\\s+", "").toUpperCase(UK);
    }

    /** Surname plus initials, e.g. «Крисак Л. (п.)» → (крисак, л). */
    record Name(String surname, String initials) {

        static Name parse(String text) {
            if (text == null) {
                return null;
            }
            String cleaned = text.toLowerCase(UK)
                    .replaceAll("\\(п\\.?\\)", " ")
                    .replace('’', '\'')
                    .replace('ʼ', '\'')
                    .replace('.', ' ')
                    .strip();
            if (cleaned.isEmpty()) {
                return null;
            }
            List<String> tokens = new ArrayList<>(Arrays.asList(cleaned.split("\\s+")));
            String surname = tokens.removeFirst();
            return new Name(surname, String.join("", tokens));
        }

        boolean sameAs(Name other) {
            if (!surname.equals(other.surname)) {
                return false;
            }
            if (initials.isEmpty() || other.initials.isEmpty()) {
                return true;
            }
            return initials.startsWith(other.initials) || other.initials.startsWith(initials);
        }
    }
}
