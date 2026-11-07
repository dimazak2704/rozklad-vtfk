package ua.vtc.rozklad.core.shared.error;

/**
 * Kind of a schedule conflict (DOMAIN.md §5). The first seven are hard violations listed in
 * CONTRACTS.md; the rest are soft warnings shown in the editor and metrics.
 */
public enum ConflictKind {
    TEACHER,
    GROUP,
    ROOM,
    CAPACITY,
    ROOM_TYPE,
    SHIFT,
    UNAVAILABLE,
    WINDOW,
    BROKEN_DAY,
    DAY_OVERLOAD,
    DISCIPLINE_REPEAT,
    UNWANTED
}
