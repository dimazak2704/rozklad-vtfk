package ua.vtc.rozklad.gateway;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Key of the Redis token bucket for {@code /api/public/**}: the client IP address. The gateway is
 * the edge (no trusted proxy in front), so {@code X-Forwarded-For} is deliberately ignored —
 * a client could forge it to escape the limit.
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
