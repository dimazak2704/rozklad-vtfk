package ua.vtc.rozklad.notification.documents;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.notification.config.DocumentProperties;
import ua.vtc.rozklad.notification.delivery.MediaResolver;
import ua.vtc.rozklad.notification.web.NotFoundException;

/**
 * Order documents: DOCX (POI) → PDF (LibreOffice) → PNG (PDFBox). Published orders are cached
 * on disk; drafts (admin preview) are rendered on every request. Also resolves the order picture
 * for queued Telegram photos ({@code order-png:<orderId>}).
 */
@Service
public class OrderDocuments implements MediaResolver {

    public static final String ORDER_PNG_PREFIX = "order-png:";

    private final OrderDocxRenderer docxRenderer;
    private final DocxToPdfConverter pdfConverter;
    private final PdfPngRenderer pngRenderer;
    private final PublishedOrders publishedOrders;
    private final DocumentCache cache;
    private final DocumentProperties properties;

    public OrderDocuments(OrderDocxRenderer docxRenderer, DocxToPdfConverter pdfConverter,
                          PdfPngRenderer pngRenderer, PublishedOrders publishedOrders, DocumentCache cache,
                          DocumentProperties properties) {
        this.docxRenderer = docxRenderer;
        this.pdfConverter = pdfConverter;
        this.pngRenderer = pngRenderer;
        this.publishedOrders = publishedOrders;
        this.cache = cache;
        this.properties = properties;
    }

    public boolean canRenderPictures() {
        return pdfConverter.isAvailable();
    }

    /** Draft preview (not cached). */
    public RenderedDocument render(OrderPayload order, DocumentFormat format) {
        Integer parentNumber = publishedOrders.parentNumber(order);
        byte[] docx = docxRenderer.render(order, parentNumber);
        byte[] content = switch (format) {
            case DOCX -> docx;
            case PDF -> pdfConverter.toPdf(docx);
            case PNG -> pngRenderer.render(pdfConverter.toPdf(docx), properties.pngScale());
        };
        return new RenderedDocument(content, format, fileName(order, format), null);
    }

    /** A published order; 404 when the order is unknown here or not published. */
    public RenderedDocument published(UUID orderId, DocumentFormat format) {
        PublishedOrder stored = publishedOrders.find(orderId)
                .orElseThrow(() -> new NotFoundException("Розпорядження не знайдено або ще не опубліковано."));
        OrderPayload order = stored.getPayload();
        String version = stored.getPayloadHash().substring(0, 16);
        byte[] content = switch (format) {
            case DOCX -> docx(stored, version);
            case PDF -> pdf(stored, version);
            case PNG -> cache.get(key(orderId, version, DocumentFormat.PNG),
                    () -> pngRenderer.render(pdf(stored, version), properties.pngScale()));
        };
        return new RenderedDocument(content, format, fileName(order, format), version + "-" + format.extension());
    }

    @Override
    public Media resolve(String mediaRef) {
        if (mediaRef == null || !mediaRef.startsWith(ORDER_PNG_PREFIX)) {
            throw new MediaUnavailableException("Невідоме медіа: " + mediaRef);
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(mediaRef.substring(ORDER_PNG_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new MediaUnavailableException("Некоректне посилання на медіа: " + mediaRef);
        }
        try {
            RenderedDocument png = published(orderId, DocumentFormat.PNG);
            return new Media(png.content(), png.fileName());
        } catch (NotFoundException e) {
            throw new MediaUnavailableException("Розпорядження " + orderId + " не знайдено.");
        } catch (ConverterUnavailableException e) {
            throw new MediaUnavailableException(e.getMessage());
        }
    }

    public static String orderPngRef(UUID orderId) {
        return ORDER_PNG_PREFIX + orderId;
    }

    private byte[] docx(PublishedOrder stored, String version) {
        return cache.get(key(stored.getId(), version, DocumentFormat.DOCX),
                () -> docxRenderer.render(stored.getPayload(), publishedOrders.parentNumber(stored.getPayload())));
    }

    private byte[] pdf(PublishedOrder stored, String version) {
        if (!pdfConverter.isAvailable()) {
            throw new ConverterUnavailableException("SOFFICE_PATH не задано");
        }
        return cache.get(key(stored.getId(), version, DocumentFormat.PDF),
                () -> pdfConverter.toPdf(docx(stored, version)));
    }

    private static String key(UUID orderId, String version, DocumentFormat format) {
        return orderId + "-" + version + "." + format.extension();
    }

    /** ASCII file name: {@code rozporiadzhennia-35-2026-10-19.pdf}, {@code dopovnennia-36-…}, {@code …-chernetka-…}. */
    static String fileName(OrderPayload order, DocumentFormat format) {
        String kind = order.kind() == OrderKind.AMENDMENT ? "dopovnennia" : "rozporiadzhennia";
        String number = order.number() == null ? "chernetka" : order.number().toString();
        return kind + "-" + number + "-" + order.date() + "." + format.extension();
    }

    /** {@code etag} is null for drafts (not cacheable). */
    public record RenderedDocument(byte[] content, DocumentFormat format, String fileName, String etag) {
    }
}
