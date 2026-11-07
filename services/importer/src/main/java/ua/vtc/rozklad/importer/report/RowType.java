package ua.vtc.rozklad.importer.report;

import java.util.EnumSet;
import java.util.Set;

/** What a report row is about. The first four are catalog entities that mappings can resolve. */
public enum RowType {
    TEACHER, DISCIPLINE, GROUP, ROOM,
    /** A parsed lesson or a conflict between lessons. */
    LESSON,
    /** A spreadsheet/document cell that could not be interpreted. */
    CELL,
    BELLS,
    /** Hours of a discipline program (structure table, sums). */
    HOURS,
    /** A row of an order document. */
    ORDER,
    /** A row of a curriculum. */
    CURRICULUM,
    /** Catalog snapshot from core (availability). */
    CATALOG,
    /** The uploaded file as a whole. */
    FILE;

    public static final Set<RowType> ENTITIES = EnumSet.of(TEACHER, DISCIPLINE, GROUP, ROOM);

    public boolean isEntity() {
        return ENTITIES.contains(this);
    }
}
