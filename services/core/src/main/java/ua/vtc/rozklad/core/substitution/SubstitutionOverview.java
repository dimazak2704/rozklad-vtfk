package ua.vtc.rozklad.core.substitution;

import java.time.LocalDate;

/** Daily substitution figures for the dispatcher dashboard (read API for the order module). */
public interface SubstitutionOverview {

    SubstitutionDaySummary summarize(LocalDate date);
}
