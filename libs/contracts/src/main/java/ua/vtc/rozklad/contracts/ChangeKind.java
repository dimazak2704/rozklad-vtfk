package ua.vtc.rozklad.contracts;

/** Kind of a schedule change (заміна) on a concrete date. */
public enum ChangeKind {
    REPLACE_TEACHER,
    REPLACE_DISCIPLINE,
    SWAP,
    CANCEL,
    ADDITIONAL,
    ROOM_CHANGE,
    BLOCK,
    DISCIPLINE_FINISHED
}
