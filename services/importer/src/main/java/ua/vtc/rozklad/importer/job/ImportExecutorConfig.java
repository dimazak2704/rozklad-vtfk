package ua.vtc.rozklad.importer.job;

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
        return executor;
    }
}
