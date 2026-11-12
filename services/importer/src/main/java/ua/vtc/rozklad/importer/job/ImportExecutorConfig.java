package ua.vtc.rozklad.importer.job;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ua.vtc.rozklad.importer.config.ImportProperties;

/**
 * Bounded pool for parsing and applying: a large workbook in POI needs hundreds of MB, so only
 * {@code app.import.workers} jobs run at once and at most {@code queue-capacity} wait.
 */
@Configuration(proxyBeanMethods = false)
public class ImportExecutorConfig {

    public static final String EXECUTOR = "importJobExecutor";

    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor importJobExecutor(ImportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(ImportExecutorConfig::withSubmitterMdc);
        return executor;
    }

    /** The job runs with the MDC of the request that queued it: its log lines and calls to core keep the request id. */
    static Runnable withSubmitterMdc(Runnable task) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            if (submitted == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(submitted);
            }
            try {
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
