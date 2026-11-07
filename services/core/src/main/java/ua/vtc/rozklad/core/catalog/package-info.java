/**
 * Catalog module: departments, specialties, cycle commissions, teachers (+aliases, competences), disciplines (+aliases), buildings, rooms, groups and subgroups.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Catalog")
package ua.vtc.rozklad.core.catalog;

import org.springframework.modulith.ApplicationModule;
