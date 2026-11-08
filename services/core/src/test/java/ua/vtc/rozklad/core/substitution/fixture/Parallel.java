package ua.vtc.rozklad.core.substitution.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Starts several calls at the same moment (one thread each, released by a barrier) and returns
 * what each call returned or threw, in the order of the tasks. Used to prove that two parallel
 * requests cannot both pass a read-then-write check.
 */
public final class Parallel {

    private Parallel() {
    }

    @SafeVarargs
    public static List<Object> run(Callable<?>... tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
