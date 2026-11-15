package ua.vtc.rozklad.core.calendar;

import java.util.Objects;
import java.util.UUID;

/**
 * What the rules and views need to know about the kind of a period: its code and name as the college writes
 * them («НС», «Національний спротив»), its colour and its effect.
 *
 * @param color {@code #RRGGBB} or {@code null}
 */
public record PeriodKindInfo(UUID id, String code, String name, String color, PeriodEffect effect) {

    public PeriodKindInfo {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effect, "effect");
    }

    public static PeriodKindInfo of(PeriodKind kind) {
        return new PeriodKindInfo(kind.getId(), kind.getCode(), kind.getName(), kind.getColor(), kind.getEffect());
    }

    /** The builtin kind a legacy kind maps to, with the values of migration V1_203. */
    public static PeriodKindInfo legacy(CalendarPeriodKind kind) {
        return new PeriodKindInfo(kind.builtinKindId(), kind.builtinCode(), kind.builtinName(), kind.builtinColor(),
                kind.builtinEffect());
    }
}
