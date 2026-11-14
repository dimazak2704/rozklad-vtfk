package ua.vtc.rozklad.core.settings.internal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The production {@link ua.vtc.rozklad.core.settings.PublicHistory} for {@code @WebMvcTest} slices of public
 * controllers; the slice declares {@code @MockitoBean SettingsService} for the number of days and imports
 * {@link ua.vtc.rozklad.core.support.TestClock.Configuration} to fix today.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PublicHistoryWindow.class)
public class PublicHistoryTestConfiguration {
}
