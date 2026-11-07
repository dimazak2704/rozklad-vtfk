/**
 * Order module: orders (розпорядження): drafts per date, numbering, items, notes, publication, amendments.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Order")
package ua.vtc.rozklad.core.order;

import org.springframework.modulith.ApplicationModule;
