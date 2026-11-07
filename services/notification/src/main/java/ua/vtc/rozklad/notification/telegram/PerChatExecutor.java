package ua.vtc.rozklad.notification.telegram;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on virtual threads; tasks with the same key (a chat) run one after another in
 * submission order, so a wizard never sees its own clicks out of order. Different chats run in parallel.
 */
public final class PerChatExecutor implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public void submit(long key, Runnable task) {
        tails.compute(key, (ignoredKey, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<Void> next = previous.handleAsync((ignoredResult, ignoredError) -> {
                task.run();
                return null;
            }, executor);
            next.whenComplete((ignoredResult, ignoredError) -> tails.remove(key, next));
            return next;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
