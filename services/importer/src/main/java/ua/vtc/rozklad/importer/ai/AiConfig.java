package ua.vtc.rozklad.importer.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ua.vtc.rozklad.importer.config.AiProperties;

/**
 * Chooses the AI adapter: {@code fake} → {@link FakeAiAdapter}; {@code anthropic} with a key →
 * {@link AnthropicAiAdapter}; otherwise {@link DisabledAiAdapter}.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    AiExtractionPort aiExtractionPort(AiProperties properties) {
        if (AiProperties.PROVIDER_FAKE.equals(properties.provider())) {
            log.warn("AI provider is FAKE: answers come only from scripted test data");
            return new FakeAiAdapter();
        }
        if (!AiProperties.PROVIDER_ANTHROPIC.equals(properties.provider())) {
            throw new IllegalStateException("Unknown app.ai.provider: " + properties.provider());
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.info("ANTHROPIC_API_KEY is empty: AI extraction is disabled, deterministic parsers still work");
            return new DisabledAiAdapter();
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries());
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        AnthropicClient client = builder.build();
        log.info("AI extraction via Anthropic: text model {}, vision model {}", properties.modelText(),
                properties.modelVision());
        return new ClosingAnthropicAdapter(client);
    }

    /** Closes the SDK client (HTTP pool) together with the context. */
    static final class ClosingAnthropicAdapter extends AnthropicAiAdapter implements AutoCloseable {

        private final AnthropicClient client;

        ClosingAnthropicAdapter(AnthropicClient client) {
            super(client);
            this.client = client;
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
