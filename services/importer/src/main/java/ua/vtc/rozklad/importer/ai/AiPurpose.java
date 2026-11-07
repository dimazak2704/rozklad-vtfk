package ua.vtc.rozklad.importer.ai;

/** Why the model was called; stored with every usage row. */
public enum AiPurpose {
    /** Order photo/scan → order rows (vision). */
    ORDER_DOCUMENT(true),
    /** Curriculum scan → per-semester rows (vision). */
    CURRICULUM(true),
    /** Discipline program text without a structure table → hours (text). */
    PROGRAM_HOURS(false),
    /** Free text of a teacher in the bot → absence (text). */
    ABSENCE_TEXT(false);

    private final boolean vision;

    AiPurpose(boolean vision) {
        this.vision = vision;
    }

    public boolean vision() {
        return vision;
    }
}
