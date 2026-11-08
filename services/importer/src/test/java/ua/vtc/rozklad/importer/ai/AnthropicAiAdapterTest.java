package ua.vtc.rozklad.importer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real SDK adapter against a local stub of the Messages API: request shape (model, effort,
 * JSON schema, base64 image before the instruction, API key header) and response handling
 * (thinking blocks skipped, usage, refusal, provider errors).
 */
class AnthropicAiAdapterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private HttpServer server;
    private AnthropicClient client;
    private AnthropicAiAdapter adapter;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // SDK сам обирає схему автентифікації (x-api-key або Bearer) — перевіряємо лише, що ключ передано
            apiKey.set(exchange.getRequestHeaders().entrySet().stream()
                    .filter(header -> header.getKey().equalsIgnoreCase("x-api-key")
                            || header.getKey().equalsIgnoreCase("authorization"))
                    .map(header -> header.getValue().getFirst().replace("Bearer ", ""))
                    .findFirst().orElse(null));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = AnthropicOkHttpClient.builder()
                .apiKey("sk-ant-api03-test-only")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .timeout(Duration.ofSeconds(10))
                .build();
        adapter = new AnthropicAiAdapter(client);
    }

    @AfterEach
    void stopStub() {
        client.close();
        server.stop(0);
    }

    @Test
    void sendsSchemaEffortAndImageAndReadsTextAndUsage() throws Exception {
        responseBody = message("end_turn", "{\\\"number\\\":35}");

        AiResponse response = adapter.complete(request());

        assertThat(response.stop()).isEqualTo(AiResponse.Stop.COMPLETE);
        assertThat(response.text()).isEqualTo("{\"number\":35}");
        assertThat(response.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(response.inputTokens()).isEqualTo(1240);
        assertThat(response.outputTokens()).isEqualTo(56);

        JsonNode sent = json.readTree(requestBody.get());
        assertThat(apiKey.get()).isEqualTo("sk-ant-api03-test-only");
        assertThat(sent.get("model").asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(sent.get("max_tokens").asLong()).isEqualTo(16000);
        assertThat(sent.get("system").asString()).isEqualTo("система");
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.at("/output_config/effort").asString()).isEqualTo("medium");
        assertThat(sent.at("/output_config/format/type").asString()).isEqualTo("json_schema");
        assertThat(sent.at("/output_config/format/schema/additionalProperties").asBoolean(true)).isFalse();
        assertThat(sent.at("/output_config/format/schema/properties/number/anyOf/1/type").asString()).isEqualTo("null");
        JsonNode content = sent.at("/messages/0/content");
        assertThat(content.get(0).get("type").asString()).isEqualTo("image");
        assertThat(content.get(0).at("/source/type").asString()).isEqualTo("base64");
        assertThat(content.get(0).at("/source/media_type").asString()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(content.get(0).at("/source/data").asString())).containsExactly(1, 2, 3);
        assertThat(content.get(1).get("type").asString()).isEqualTo("text");
        assertThat(content.get(1).get("text").asString()).isEqualTo("прочитай розпорядження");
    }

    @Test
    void refusalAndMaxTokensAreMappedToStops() {
        responseBody = message("refusal", "");
        assertThat(adapter.complete(request()).stop()).isEqualTo(AiResponse.Stop.REFUSED);
        responseBody = message("max_tokens", "{\\\"rows\\\":[");
        assertThat(adapter.complete(request()).stop()).isEqualTo(AiResponse.Stop.TRUNCATED);
    }

    @Test
    void overloadIsUnavailableAndBadRequestIsAnExtractionError() {
        status = 529;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThatThrownBy(() -> adapter.complete(request())).isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("перевантажений");
        status = 400;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad schema\"}}";
        // r3-ui-real-32: людський текст без коду HTTP
        assertThatThrownBy(() -> adapter.complete(request())).isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("налаштування розпізнавання").hasMessageNotContaining("HTTP");
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"messages.0.content.0.image.source.base64.data: Could not process image\"}}";
        assertThatThrownBy(() -> adapter.complete(request())).isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("не зміг прочитати цей файл").hasMessageNotContaining("HTTP");
    }

    private static AiRequest request() {
        return new AiRequest(AiPurpose.ORDER_DOCUMENT, "claude-sonnet-5-5", "система",
                List.of(new AiDocument("image/jpeg", new byte[] {1, 2, 3})), "прочитай розпорядження",
                AiSchemas.order(), 16000, "medium");
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"%s"}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":56,"cache_creation_input_tokens":40,"cache_read_input_tokens":0}}
                """.formatted(text, stopReason);
    }
}
