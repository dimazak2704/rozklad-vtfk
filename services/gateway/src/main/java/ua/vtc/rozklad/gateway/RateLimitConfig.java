package ua.vtc.rozklad.gateway;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Key of the Redis token bucket for {@code /api/public/**}: the client IP address as the server sees it.
 * Without a forward-headers strategy that is the socket address and forwarding headers are ignored (a
 * client could forge them). In production Caddy is the only client and the gateway runs with
 * {@code SERVER_FORWARD_HEADERS_STRATEGY=native}: the address comes from the {@code X-Forwarded-For} Caddy
 * sets, so the bucket is per browser; Caddy strips a client's {@code Forwarded}, which Reactor Netty would
 * prefer ({@code ForwardedClientRateLimitIT}).
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    static final String UNKNOWN_CLIENT = "unknown";

    @Bean
    KeyResolver clientAddressKeyResolver() {
        return exchange -> Mono.just(Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(address -> address.getHostAddress())
                .orElse(UNKNOWN_CLIENT));
    }
}
