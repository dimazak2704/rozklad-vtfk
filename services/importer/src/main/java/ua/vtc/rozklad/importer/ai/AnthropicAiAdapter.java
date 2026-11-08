package ua.vtc.rozklad.importer.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AiExtractionPort} over the official Anthropic Java SDK: Messages API with structured
 * outputs ({@code output_config.format} = JSON schema) and an explicit effort level. Thinking is
 * left at the model default (adaptive). Documents go as base64 PDF/image blocks before the text
 * instruction. The response is read by block type (thinking blocks are skipped) and checked for a
 * refusal or a cut-off before use. Nothing of the documents or the API key is logged.
 */
public class AnthropicAiAdapter implements AiExtractionPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiAdapter.class);

    private final AnthropicClient client;

    public AnthropicAiAdapter(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        Message message;
        try {
            message = client.messages().create(params(request));
        } catch (AnthropicServiceException e) {
            throw translate(request, e);
        } catch (AnthropicIoException e) {
            log.warn("AI call {} failed with a network error: {}", request.purpose(), e.toString());
            throw new AiUnavailableException("Сервіс ШІ недоступний (мережева помилка). Спробуйте пізніше.", e);
        }
        return response(message);
    }

    static MessageCreateParams params(AiRequest request) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        Base64.Encoder base64 = Base64.getEncoder();
        for (AiDocument document : request.documents()) {
            String data = base64.encodeToString(document.content());
            if (document.pdf()) {
                blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(data).build())
                        .build()));
            } else {
                blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .data(data)
                                .mediaType(Base64ImageSource.MediaType.of(document.mediaType()))
                                .build())
                        .build()));
            }
        }
        blocks.add(ContentBlockParam.ofText(request.instruction()));
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        request.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        return MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .system(request.system())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(request.effort()))
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build())
                .addUserMessageOfContentBlockParams(blocks)
                .build();
    }

    static AiResponse response(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(textBlock -> text.append(textBlock.text()));
        }
        AiResponse.Stop stop = AiResponse.Stop.COMPLETE;
        StopReason reason = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(reason)) {
            stop = AiResponse.Stop.REFUSED;
        } else if (StopReason.MAX_TOKENS.equals(reason) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(reason)) {
            stop = AiResponse.Stop.TRUNCATED;
        }
        Usage usage = message.usage();
        long input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L)
                + usage.cacheReadInputTokens().orElse(0L);
        return new AiResponse(text.toString(), message.model().asString(), input, usage.outputTokens(), stop);
    }

    private static RuntimeException translate(AiRequest request, AnthropicServiceException e) {
        int status = e.statusCode();
        log.warn("AI call {} on {} failed: HTTP {} {} (request id {})", request.purpose(), request.model(), status,
                e.apiMessage().orElse(""), e.requestId().orElse("-"));
        if (status == 429 || status == 529 || status >= 500) {
            return new AiUnavailableException("Сервіс ШІ перевантажений або тимчасово недоступний. Спробуйте пізніше.", e);
        }
        if (status == 401 || status == 403) {
            return new AiUnavailableException("Ключ доступу до ШІ недійсний або не має прав. Зверніться до адміністратора.", e);
        }
        if (status == 413) {
            return new AiExtractionException("Файл завеликий для розпізнавання ШІ — зменште його розмір.", e);
        }
        if (status == 400 && aboutTheDocument(e.apiMessage().orElse(""))) {
            return new AiExtractionException("Сервіс ШІ не зміг прочитати цей файл. Перевірте, що він відкривається і "
                    + "читається, збережіть його ще раз (JPEG, PNG або PDF) або внесіть дані вручну.", e);
        }
        // інші відмови — налаштування сервісу (модель, параметри): адміністратору, подробиці в журналі
        return new AiExtractionException("Сервіс ШІ не прийняв запит через налаштування розпізнавання. Повідомте "
                + "адміністратора системи — подробиці записано в журнал імпорту.", e);
    }

    /** The API names the image or the PDF as the rejected part of the request. */
    static boolean aboutTheDocument(String apiMessage) {
        String message = apiMessage.toLowerCase(java.util.Locale.ROOT);
        return message.contains("image") || message.contains("pdf") || message.contains("document");
    }
}
