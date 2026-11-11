/**
 * Identity module: current user, teacher invites, accounts in the sign-in service auth, teacher ↔ account link.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Identity")
package ua.vtc.rozklad.core.identity;

import org.springframework.modulith.ApplicationModule;
