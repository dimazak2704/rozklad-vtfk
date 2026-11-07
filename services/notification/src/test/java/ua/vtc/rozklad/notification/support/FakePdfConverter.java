package ua.vtc.rozklad.notification.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import ua.vtc.rozklad.notification.documents.ConverterUnavailableException;
import ua.vtc.rozklad.notification.documents.DocxToPdfConverter;

/**
 * LibreOffice is not on the test host: a one-page A4 PDF made by PDFBox stands in for the
 * conversion, so the PNG path (PDFBox rendering) and the delivery of pictures are still exercised.
 */
public class FakePdfConverter implements DocxToPdfConverter {

    private final AtomicInteger conversions = new AtomicInteger();
    private volatile boolean available = true;

    public void reset() {
        conversions.set(0);
        available = true;
    }

    public void setAvailable(boolean value) {
        this.available = value;
    }

    public int conversions() {
        return conversions.get();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public byte[] toPdf(byte[] docx) {
        if (!available) {
            throw new ConverterUnavailableException("test");
        }
        if (docx.length < 4 || docx[0] != 'P' || docx[1] != 'K') {
            throw new IllegalArgumentException("not a DOCX (ZIP) document");
        }
        conversions.incrementAndGet();
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
