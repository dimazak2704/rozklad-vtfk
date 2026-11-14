package ua.vtc.rozklad.importer.timetable;

import ua.vtc.rozklad.importer.job.ImportParseException;

/** A sheet far bigger than any timetable ({@link SheetGrid}): the import stops with a plain message. */
final class SheetTooLargeException extends ImportParseException {

    SheetTooLargeException(String sheetName) {
        super("Аркуш «" + sheetName + "» завеликий для розкладу (понад " + SheetGrid.MAX_CELLS / 1_000_000
                + " млн клітинок або забагато об’єднаних клітинок). Видаліть зайві рядки й стовпці або "
                + "збережіть розклад в окремий файл і завантажте знову.");
    }
}
