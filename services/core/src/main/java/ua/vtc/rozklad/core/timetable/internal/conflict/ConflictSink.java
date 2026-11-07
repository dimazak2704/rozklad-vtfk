package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/** Collects conflicts of all rules and returns them in a stable order (day, pair, kind, message). */
final class ConflictSink {

    private static final Comparator<ConflictDto> ORDER = Comparator
            .comparing(ConflictDto::date, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ConflictDto::weekday, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(ConflictDto::pair)
            .thenComparing(ConflictDto::kind)
            .thenComparing(ConflictDto::message);

    private final List<ConflictDto> conflicts = new ArrayList<>();

    void add(ConflictDto conflict) {
        conflicts.add(conflict);
    }

    ConflictReport report() {
        return new ConflictReport(
                conflicts.stream().filter(ConflictDto::isHard).sorted(ORDER).toList(),
                conflicts.stream().filter(conflict -> !conflict.isHard()).sorted(ORDER).toList());
    }
}
