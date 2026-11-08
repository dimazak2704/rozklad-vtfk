package ua.vtc.rozklad.contracts;

/**
 * Kind of a schedule change (заміна) on a concrete date. Append values only: consumers switch over
 * the whole enum. {@code MERGE_SUBGROUPS}: the teacher of the other subgroup of the same discipline
 * at the same pair takes both subgroups together (the absent teacher's subgroup joins).
 */
public enum ChangeKind {
    REPLACE_TEACHER,
    REPLACE_DISCIPLINE,
    SWAP,
    CANCEL,
    ADDITIONAL,
    ROOM_CHANGE,
    BLOCK,
    DISCIPLINE_FINISHED,
    MERGE_SUBGROUPS
}
