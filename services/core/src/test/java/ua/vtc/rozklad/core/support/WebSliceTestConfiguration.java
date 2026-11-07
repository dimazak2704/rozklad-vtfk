package ua.vtc.rozklad.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import ua.vtc.rozklad.core.shared.security.CurrentUserProvider;
import ua.vtc.rozklad.core.shared.security.ProblemDetailSecurityHandlers;
import ua.vtc.rozklad.core.shared.security.SecurityConfig;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.shared.web.JacksonConfig;

/**
 * Production security, JSON conventions and clock for {@code @WebMvcTest} slices:
 * <pre>{@code
 * @WebMvcTest(MyController.class)
 * @Import(WebSliceTestConfiguration.class)
 * class MyControllerTest { ... mockMvc.perform(get(...).with(TestJwts.admin())) ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, ProblemDetailSecurityHandlers.class, CurrentUserProvider.class, TimeConfig.class,
        JacksonConfig.class})
public class WebSliceTestConfiguration {
}
