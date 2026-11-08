package ua.vtc.rozklad.core.substitution.internal.options;

/** Factors of the ranking that the offline evaluation may switch off one at a time (ablation). */
enum RankingFactor {
    LOAD,
    COVER_BURDEN,
    BEHIND_PLAN,
    CLOSES_WINDOW,
    WAITING,
    LONG_RUN,
    OVERRUN,
    BUNDLE,
    UNWANTED,
    DECLINED_TODAY,
    ROOM_TYPE,
    WITH_GROUP_TODAY
}
