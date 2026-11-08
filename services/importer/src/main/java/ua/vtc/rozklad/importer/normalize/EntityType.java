package ua.vtc.rozklad.importer.normalize;

import ua.vtc.rozklad.importer.report.RowType;

/** Catalog entity kinds that an admin can map (CONTRACTS §5 {@code mappings[].type}). */
public enum EntityType {
    TEACHER(RowType.TEACHER, "викладач", "викладачів"),
    DISCIPLINE(RowType.DISCIPLINE, "дисципліна", "дисциплін"),
    GROUP(RowType.GROUP, "група", "груп"),
    ROOM(RowType.ROOM, "аудиторія", "аудиторій");

    private final RowType rowType;
    private final String label;
    private final String genitivePlural;

    EntityType(RowType rowType, String label, String genitivePlural) {
        this.rowType = rowType;
        this.label = label;
        this.genitivePlural = genitivePlural;
    }

    /** «нових груп» — for «не створює нових …». */
    public String genitivePlural() {
        return genitivePlural;
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
