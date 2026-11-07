package ua.vtc.rozklad.core.timetable.internal.conflict;

/** One rule of DOMAIN.md §5; adds its conflicts to the sink. */
interface ConflictRule {

    void check(LessonIndex index, ConflictFacts facts, ConflictSink sink);
}
