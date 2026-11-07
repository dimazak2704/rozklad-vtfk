package ua.vtc.rozklad.notification.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces Telegram, core, importer, LibreOffice and the clock in integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoubles {

    @Bean
    @Primary
    FakeTelegramClient fakeTelegramClient() {
        return new FakeTelegramClient();
    }

    @Bean
    @Primary
    FakeCoreClient fakeCoreClient() {
        return new FakeCoreClient();
    }

    @Bean
    @Primary
    FakeImporterClient fakeImporterClient() {
        return new FakeImporterClient();
    }

    @Bean
    @Primary
    FakePdfConverter fakePdfConverter() {
        return new FakePdfConverter();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
