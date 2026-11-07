package ua.vtc.rozklad.importer.normalize;

import ua.vtc.rozklad.importer.report.RowType;

/** Catalog entity kinds that an admin can map (CONTRACTS §5 {@code mappings[].type}). */
public enum EntityType {
    TEACHER(RowType.TEACHER, "викладач"),
    DISCIPLINE(RowType.DISCIPLINE, "дисципліна"),
    GROUP(RowType.GROUP, "група"),
    ROOM(RowType.ROOM, "аудиторія");

    private final RowType rowType;
    private final String label;

    EntityType(RowType rowType, String label) {
        this.rowType = rowType;
        this.label = label;
    }

    public RowType rowType() {
        return rowType;
    }

    /** Ukrainian noun for messages. */
    public String label() {
        return label;
    }

    /** Core keeps aliases only for teachers and disciplines. */
    public boolean supportsAliases() {
        return this == TEACHER || this == DISCIPLINE;
    }
}
