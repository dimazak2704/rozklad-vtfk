/**
 * Shared kernel: entity base classes, errors (ProblemDetail), time, security, SSE, messaging
 * infrastructure. Open module: every other module may use all of its types, but it must never
 * depend on another module.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package ua.vtc.rozklad.core.shared;

import org.springframework.modulith.ApplicationModule;
