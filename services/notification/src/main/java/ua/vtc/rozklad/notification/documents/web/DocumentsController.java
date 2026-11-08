package ua.vtc.rozklad.notification.documents.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.notification.documents.DocumentFormat;
import ua.vtc.rozklad.notification.documents.OrderDocuments;
import ua.vtc.rozklad.notification.documents.OrderDocuments.RenderedDocument;
import ua.vtc.rozklad.notification.web.FieldValidationException;
import ua.vtc.rozklad.notification.web.NotFoundException;

/**
 * Order documents (CONTRACTS §6): published orders are public, drafts are rendered for ADMIN from
 * the posted {@code OrderPayload}.
 */
@RestController
@RequestMapping("/api/documents/orders")
@Tag(name = "Документи розпоряджень")
public class DocumentsController {

    private static final String FORMATS = "docx, pdf або png";

    private static final List<String> REQUIRED = List.of("id", "date", "kind", "status");

    private final OrderDocuments documents;
    private final JsonMapper jsonMapper;

    public DocumentsController(OrderDocuments documents, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.documents = documents;
    }

    @GetMapping("/{orderId}.{format}")
    @Operation(summary = "DOCX/PDF/PNG опублікованого розпорядження (публічно)")
    public ResponseEntity<byte[]> published(@PathVariable UUID orderId, @PathVariable String format,
                                            WebRequest request) {
        DocumentFormat documentFormat = DocumentFormat.fromExtension(format)
                .orElseThrow(() -> new NotFoundException("Формат «" + format + "» не підтримується: " + FORMATS + "."));
        RenderedDocument document = documents.published(orderId, documentFormat);
        String etag = "\"" + document.etag() + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(document.format().mediaType())
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(document).toString())
                .body(document.content());
    }

    @PostMapping("/render")
    @Operation(summary = "Попередній перегляд чернетки розпорядження (ADMIN)")
    public ResponseEntity<byte[]> render(@RequestParam(defaultValue = "pdf") String format,
                                         @RequestBody JsonNode body) {
        // формат перевіряється до тіла, а тіло читається тут, щоб назвати обов'язкові поля, яких бракує
        DocumentFormat documentFormat = DocumentFormat.fromExtension(format)
                .orElseThrow(() -> new FieldValidationException("format", "Формат має бути " + FORMATS + "."));
        RenderedDocument document = documents.render(payload(body), documentFormat);
        return ResponseEntity.ok()
                .contentType(document.format().mediaType())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(document).toString())
                .body(document.content());
    }

    /** {@code OrderPayload} of the body; a missing required field is a 400 on that field. */
    private OrderPayload payload(JsonNode body) {
        if (!body.isObject()) {
            throw new FieldValidationException("body", "Очікується об'єкт розпорядження.");
        }
        for (String required : REQUIRED) {
            if (!body.hasNonNull(required)) {
                throw new FieldValidationException(required, "Обов'язкове поле.");
            }
        }
        try {
            return jsonMapper.treeToValue(body, OrderPayload.class);
        } catch (JacksonException unreadable) {
            String field = unreadable.getPath().stream()
                    .map(reference -> reference.getPropertyName() != null ? reference.getPropertyName()
                            : "[" + reference.getIndex() + "]")
                    .reduce((left, right) -> right.startsWith("[") ? left + right : left + "." + right)
                    .orElse("body");
            throw new FieldValidationException(field, "Значення має неправильний тип або формат.");
        }
    }

    private static ContentDisposition disposition(RenderedDocument document) {
        ContentDisposition.Builder builder = document.format() == DocumentFormat.DOCX
                ? ContentDisposition.attachment()
                : ContentDisposition.inline();
        return builder.filename(document.fileName()).build();
    }
}
