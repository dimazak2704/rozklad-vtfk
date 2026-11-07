/**
 * Calendar module: academic years, semesters, week parity (+overrides), bell profiles, shift windows, calendar periods.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Calendar")
package ua.vtc.rozklad.core.calendar;

import org.springframework.modulith.ApplicationModule;
