package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;
import ua.vtc.rozklad.importer.config.ImportProperties;
import ua.vtc.rozklad.importer.web.RequestIdFilter;

/** A job runs with the request id of the upload or apply request, so its calls to core forward it. */
class ImportExecutorConfigTest {

    private final ThreadPoolTaskExecutor executor = new ImportExecutorConfig().importJobExecutor(
            new ImportProperties(DataSize.ofMegabytes(20), ImportProperties.Storage.DATABASE, null, 1, 5));

    @AfterEach
    void stop() {
        executor.shutdown();
        MDC.clear();
    }

    @Test
    void aJobSeesTheRequestIdOfTheRequestThatQueuedItAndTheWorkerIsCleanAfterwards() throws Exception {
        executor.initialize();
        MDC.put(RequestIdFilter.MDC_KEY, "req-import-3");
        CompletableFuture<String> seen = new CompletableFuture<>();
        executor.execute(() -> seen.complete(MDC.get(RequestIdFilter.MDC_KEY)));
        MDC.clear();
        CompletableFuture<String> next = new CompletableFuture<>();
        executor.execute(() -> next.complete(String.valueOf(MDC.get(RequestIdFilter.MDC_KEY))));

        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("req-import-3");
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("null");
    }
}
