package ua.vtc.rozklad.importer.config;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** Replaces Tomcat's HTML error report with {@link ProblemErrorReportValve} on the embedded host. */
@Component
public class ProblemErrorReportConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // контекст уже доданий до хоста, а хост ще не стартував: він створить наш клапан замість типового
        factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
            }
        });
    }
}
