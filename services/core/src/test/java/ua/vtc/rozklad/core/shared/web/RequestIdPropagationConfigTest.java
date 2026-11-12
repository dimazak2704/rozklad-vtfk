package ua.vtc.rozklad.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The request id of the current work reaches other services (RestClient) and async tasks (TaskDecorator). */
class RequestIdPropagationConfigTest {

    private final RequestIdPropagationConfig config = new RequestIdPropagationConfig();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void outgoingCallsCarryTheRequestIdOfTheCurrentRequest() {
        RestClient.Builder shared = RestClient.builder();
        config.requestIdRestClientCustomizer().customize(shared);
        // клієнти (напр. AccountsApiConfig) беруть clone() спільного будівника
        RestClient.Builder builder = shared.clone().baseUrl("http://auth.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://auth.test/accounts")).andExpect(header(RequestIdFilter.HEADER, "req-42"))
                .andRespond(withNoContent());
        server.expect(requestTo("http://auth.test/accounts")).andExpect(headerDoesNotExist(RequestIdFilter.HEADER))
                .andRespond(withNoContent());

        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        client.get().uri("/accounts").retrieve().toBodilessEntity();
        MDC.remove(RequestIdFilter.MDC_KEY);
        client.get().uri("/accounts").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void anAsyncTaskSeesTheMdcOfTheThreadThatSubmittedItAndLeavesTheWorkerClean() throws InterruptedException {
        MDC.put(RequestIdFilter.MDC_KEY, "req-7");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = config.mdcTaskDecorator().decorate(() -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
        AtomicReference<String> after = new AtomicReference<>("not run");

        Thread worker = Thread.ofVirtual().start(() -> {
            MDC.put("worker", "own");
            task.run();
            after.set(MDC.get(RequestIdFilter.MDC_KEY) + "/" + MDC.get("worker"));
        });
        worker.join();

        assertThat(seen).hasValue("req-7");
        assertThat(after).hasValue("null/own");
    }
}
