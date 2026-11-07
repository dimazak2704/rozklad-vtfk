package ua.vtc.rozklad.importer.job;

/** What is being imported (CONTRACTS §5). */
public enum ImportKind {
    /** The college timetable workbook (DOMAIN §11). */
    TIMETABLE_XLSX,
    /** A discipline program: DOCX, legacy DOC or PDF. */
    DISCIPLINE_PROGRAM,
    /** The curriculum («навчальний план»): scanned PDF, photo or XLSX. */
    CURRICULUM,
    /** An order («розпорядження»): DOCX or a photo/scan. */
    ORDER_DOCUMENT
}
