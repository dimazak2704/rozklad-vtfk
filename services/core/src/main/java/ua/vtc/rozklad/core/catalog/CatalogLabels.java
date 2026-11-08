package ua.vtc.rozklad.core.catalog;

import ua.vtc.rozklad.contracts.RoomType;

/** Ukrainian display labels of catalog values. */
public final class CatalogLabels {

    private CatalogLabels() {
    }

    public static String roomType(RoomType type) {
        return switch (type) {
            case CLASSROOM -> "Аудиторія";
            case COMPUTER_LAB -> "Комп'ютерний клас";
            case LAB -> "Лабораторія";
            case GYM -> "Спортзал";
            case SPORTS_GROUND -> "Спортмайданчик";
            case OTHER -> "Інше приміщення";
        };
    }

    /** «ауд. 6» for numbered rooms, the name itself otherwise («с.з.»). */
    public static String room(String name) {
        return !name.isEmpty() && Character.isDigit(name.charAt(0)) ? "ауд. " + name : name;
    }

    /** «4 курс». */
    public static String course(int course) {
        return course + " курс";
    }
}
