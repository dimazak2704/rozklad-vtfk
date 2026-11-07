package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/** Target slot of a drag & drop; {@code roomId == null} keeps the current room. */
public record MoveCommand(int weekday, int pair, Parity parity, UUID roomId) {

    public MoveCommand {
        Objects.requireNonNull(parity, "parity");
    }
}
