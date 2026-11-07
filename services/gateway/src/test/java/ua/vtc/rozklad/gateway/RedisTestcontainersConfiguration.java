package ua.vtc.rozklad.gateway;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

/** Redis 8 in Docker for the rate limiter. */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer(DockerImageName.parse("redis:8.8-alpine"));
    }
}
