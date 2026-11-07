/**
 * Identity module: current user, teacher invites, Keycloak admin integration, teacher ↔ user link.
 *
 * <p>Entities and repositories live in this base package and are readable by other modules;
 * services and controllers belong in sub-packages (internal to the module).
 */
@ApplicationModule(displayName = "Identity")
package ua.vtc.rozklad.core.identity;

import org.springframework.modulith.ApplicationModule;
