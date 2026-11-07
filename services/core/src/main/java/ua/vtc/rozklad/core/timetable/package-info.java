/**
 * Timetable module: timetable versions, template lessons, dated lessons (materialization), generation jobs, public schedule queries.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Timetable")
package ua.vtc.rozklad.core.timetable;

import org.springframework.modulith.ApplicationModule;
