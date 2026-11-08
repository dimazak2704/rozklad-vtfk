package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Which gateway failures the client sees as «service unavailable» rather than an internal error. */
class GatewayErrorHandlerTest {

    @Test
    void stoppedContainerWhoseNameNoLongerResolvesIsUnavailable() {
        // Docker removes the DNS name of a stopped container: Netty reports UnknownHostException
        Throwable error = new IllegalStateException("routing failed",
                new UnknownHostException("Failed to resolve 'importer' [A(1)]"));

        GatewayErrorHandler.Problem problem = GatewayErrorHandler.classify(error);

        assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.type()).isEqualTo("service-unavailable");
        assertThat(problem.title()).isEqualTo("Сервіс тимчасово недоступний");
    }

    @Test
    void refusedConnectionAndNoRouteAreUnavailable() {
        assertThat(GatewayErrorHandler.classify(new ConnectException("Connection refused")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.classify(new NoRouteToHostException("No route to host")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void timeoutIsGatewayTimeoutAndUnknownFailureStaysInternal() {
        assertThat(GatewayErrorHandler.classify(new TimeoutException()).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(GatewayErrorHandler.classify(new IllegalStateException("bug")).status())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(GatewayErrorHandler.classify(new ResponseStatusException(HttpStatus.NOT_FOUND)).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
