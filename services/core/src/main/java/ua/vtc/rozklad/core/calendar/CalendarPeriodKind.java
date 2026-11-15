package ua.vtc.rozklad.core.calendar;

import java.util.UUID;

/**
 * The five kinds a period had before kinds became data ({@link PeriodKind}). Deprecated, kept one release:
 * requests may still send it (it resolves to the builtin kind below), responses still carry it (derived by
 * {@link #of}), and the column {@code calendar_period.kind} is still written, never read. Behaviour comes
 * from the kind's {@link PeriodEffect} only.
 */
public enum CalendarPeriodKind {
    HOLIDAY("К", "Канікули", "#FFFFCC", PeriodEffect.DAY_OFF, "a1000000-0000-4000-8000-000000000001"),
    NO_CLASSES("БЗ", "Без занять", null, PeriodEffect.DAY_OFF, "a1000000-0000-4000-8000-000000000003"),
    PRACTICE("НП", "Практика", null, PeriodEffect.SUSPEND, "a1000000-0000-4000-8000-000000000004"),
    STUDY_MODE("ФН", "Форма навчання", null, PeriodEffect.ONLINE, "a1000000-0000-4000-8000-000000000013"),
    // рішення власника 15.11.2026: у сесію звичайних пар немає
    EXAM_SESSION("І", "Сесія", null, PeriodEffect.SUSPEND, "a1000000-0000-4000-8000-000000000010");

    private final String builtinCode;
    private final String builtinName;
    private final String builtinColor;
    private final PeriodEffect builtinEffect;
    private final UUID builtinKindId;

    CalendarPeriodKind(String builtinCode, String builtinName, String builtinColor, PeriodEffect builtinEffect,
                       String builtinKindId) {
        this.builtinCode = builtinCode;
        this.builtinName = builtinName;
        this.builtinColor = builtinColor;
        this.builtinEffect = builtinEffect;
        this.builtinKindId = UUID.fromString(builtinKindId);
    }

    /** Code of the builtin kind this legacy kind maps to (К, БЗ, НП, ФН, І). */
    public String builtinCode() {
        return builtinCode;
    }

    public String builtinName() {
        return builtinName;
    }

    public String builtinColor() {
        return builtinColor;
    }

    /** Effect of the builtin kind as seeded; the dispatcher may switch it later. */
    public PeriodEffect builtinEffect() {
        return builtinEffect;
    }

    /** Fixed id of the builtin kind (migration V1_203). */
    public UUID builtinKindId() {
        return builtinKindId;
    }

    /**
     * The legacy value of a kind: the builtin К/БЗ/НП/ФН/І keep theirs, every other kind gets one by effect
     * (DAY_OFF → NO_CLASSES, SUSPEND → PRACTICE, ONLINE → STUDY_MODE, INFO → EXAM_SESSION).
     */
    public static CalendarPeriodKind of(String code, boolean builtin, PeriodEffect effect) {
        if (builtin) {
            for (CalendarPeriodKind kind : values()) {
                if (kind.builtinCode.equals(code)) {
                    return kind;
                }
            }
        }
        return switch (effect) {
            case DAY_OFF -> NO_CLASSES;
            case SUSPEND -> PRACTICE;
            case ONLINE -> STUDY_MODE;
            case INFO -> EXAM_SESSION;
        };
    }

    public static CalendarPeriodKind of(PeriodKind kind) {
        return of(kind.getCode(), kind.isBuiltin(), kind.getEffect());
    }
}
