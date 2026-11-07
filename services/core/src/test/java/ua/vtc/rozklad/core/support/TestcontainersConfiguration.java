package ua.vtc.rozklad.core.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests: PostgreSQL 18, RabbitMQ 4.4 and Redis 8 in Docker,
 * wired through {@code @ServiceConnection}. Spring caches the context, so containers start once
 * per test JVM for all tests that share the same configuration.
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestcontainersConfiguration.class)
 * class MyFeatureIT { ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18-alpine");
    public static final DockerImageName RABBITMQ = DockerImageName.parse("rabbitmq:4.4-management-alpine");
    public static final DockerImageName REDIS = DockerImageName.parse("redis:8.8-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES);
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(RABBITMQ);
    }

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer(REDIS);
    }
}
