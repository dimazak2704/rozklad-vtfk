package ua.vtc.rozklad.notification.documents;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/** PDF → one PNG (pages one under another) with PDFBox, at 2× (144 DPI) by default. */
@Component
public class PdfPngRenderer {

    /** Telegram photos must fit width + height ≤ 10000 px; more pages are a document, not a picture. */
    static final int MAX_PAGES = 5;
    private static final int PAGE_GAP = 12;

    public byte[] render(byte[] pdf, int scale) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new DocumentRenderingException("PDF has no pages");
            }
            if (pages > MAX_PAGES) {
                throw new DocumentRenderingException("Order has " + pages + " pages; PNG is limited to "
                        + MAX_PAGES + " (use PDF)");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<BufferedImage> images = new ArrayList<>(pages);
            for (int page = 0; page < pages; page++) {
                images.add(renderer.renderImage(page, scale, ImageType.RGB));
            }
            return png(stack(images, PAGE_GAP * scale));
        } catch (IOException e) {
            throw new DocumentRenderingException("Cannot render PDF to PNG: " + e.getMessage(), e);
        }
    }

    private static BufferedImage stack(List<BufferedImage> pages, int gap) {
        if (pages.size() == 1) {
            return pages.getFirst();
        }
        int width = pages.stream().mapToInt(BufferedImage::getWidth).max().orElseThrow();
        int height = pages.stream().mapToInt(BufferedImage::getHeight).sum() + gap * (pages.size() - 1);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(new Color(0xE6E6E6));
            graphics.fillRect(0, 0, width, height);
            int y = 0;
            for (BufferedImage page : pages) {
                graphics.drawImage(page, 0, y, null);
                y += page.getHeight() + gap;
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new DocumentRenderingException("No PNG writer available");
        }
        return out.toByteArray();
    }
}
