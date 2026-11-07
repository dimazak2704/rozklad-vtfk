package ua.vtc.rozklad.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.core.order.internal.draft.OrderDrafts;
import ua.vtc.rozklad.core.order.internal.publish.OrderPublication;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;

/** Order numbers stay sequential and unique when dispatchers publish at the same moment. */
@CoreIntegrationTest
class OrderNumberingIT {

    @Autowired
    private OrderDrafts drafts;

    @Autowired
    private OrderPublication publication;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ScheduleFixture fixture;
    private ExecutorService executor;

    @BeforeEach
    void semester() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        fixture.semester();
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        fixture.cleanUp();
    }

    @Test
    void concurrentPublicationsOfDifferentDatesGetConsecutiveNumbers() throws Exception {
        List<UUID> orderIds = List.of(
                drafts.draftFor(LocalDate.of(2026, 10, 20)).id(),
                drafts.draftFor(LocalDate.of(2026, 10, 21)).id(),
                drafts.draftFor(LocalDate.of(2026, 10, 22)).id(),
                drafts.draftFor(LocalDate.of(2026, 10, 23)).id());

        List<Future<OrderDto>> results = startTogether(orderIds.stream()
                .<Callable<OrderDto>>map(id -> () -> publication.publish(id, "admin")).toList());

        List<Integer> numbers = new ArrayList<>();
        for (Future<OrderDto> result : results) {
            numbers.add(result.get(60, TimeUnit.SECONDS).number());
        }
        assertThat(numbers).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void sameOrderPublishedTwiceAtOnceGetsOneNumber() throws Exception {
        UUID orderId = drafts.draftFor(LocalDate.of(2026, 10, 20)).id();

        List<Future<OrderDto>> results = startTogether(List.of(
                () -> publication.publish(orderId, "admin"),
                () -> publication.publish(orderId, "admin2")));

        int published = 0;
        int rejected = 0;
        for (Future<OrderDto> result : results) {
            try {
                assertThat(result.get(60, TimeUnit.SECONDS).number()).isEqualTo(1);
                published++;
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessRuleException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ORDER_ALREADY_PUBLISHED"));
                rejected++;
            }
        }
        assertThat(published).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // відхилена спроба не «з'їла» номер: наступне розпорядження — №2
        assertThat(publication.publish(drafts.draftFor(LocalDate.of(2026, 10, 21)).id(), "admin").number())
                .isEqualTo(2);
    }

    /** Runs the tasks in parallel threads released by one latch. */
    private <T> List<Future<T>> startTogether(List<Callable<T>> tasks) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = tasks.stream()
                .map(task -> executor.submit(() -> {
                    start.await();
                    return task.call();
                }))
                .toList();
        start.countDown();
        return futures;
    }
}
