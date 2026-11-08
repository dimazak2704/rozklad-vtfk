package ua.vtc.rozklad.notification.documents;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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

/**
 * PDF → one PNG picture of the order for phones and Telegram, at 2× (144 DPI) by default. The PDF
 * and DOCX stay A4; the picture is cropped to the printed content of the pages (one horizontal
 * bounding box for all pages, so the table columns stay aligned) with a small white margin, pages
 * one under another. A short order is a compact picture instead of an A4 sheet that is mostly
 * white, so Telegram does not shrink the table into unreadable lines.
 */
@Component
public class PdfPngRenderer {

    /** More pages are a document, not a picture. */
    static final int MAX_PAGES = 5;
    /** Telegram photo limits: width + height ≤ 10000 px, aspect ratio ≤ 20. */
    static final int MAX_WIDTH_PLUS_HEIGHT = 10_000;
    static final int MAX_ASPECT_RATIO = 20;
    /** White margin around the content, in PDF points (×scale in pixels). */
    static final int MARGIN = 12;
    private static final int PAGE_GAP = 12;
    /** A pixel darker than this (0–255, per channel) is ink. */
    private static final int INK_THRESHOLD = 235;

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
            BufferedImage picture = stack(crop(images, MARGIN * scale), PAGE_GAP * scale);
            return png(fitTelegramLimits(picture));
        } catch (IOException e) {
            throw new DocumentRenderingException("Cannot render PDF to PNG: " + e.getMessage(), e);
        }
    }

    /**
     * Each page cut to its own ink rows and to the common ink columns of all pages, plus the margin.
     * Blank pages are dropped; a document with nothing printed at all is left as it is.
     */
    static List<BufferedImage> crop(List<BufferedImage> pages, int margin) {
        List<Rectangle> boxes = pages.stream().map(PdfPngRenderer::inkBox).toList();
        Rectangle columns = null;
        for (Rectangle box : boxes) {
            if (box != null) {
                columns = columns == null ? new Rectangle(box) : columns.union(box);
            }
        }
        if (columns == null) {
            return pages;
        }
        List<BufferedImage> cropped = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            BufferedImage page = pages.get(i);
            Rectangle box = boxes.get(i);
            if (box == null) {
                continue;
            }
            int left = Math.max(0, columns.x - margin);
            int right = Math.min(page.getWidth(), columns.x + columns.width + margin);
            int top = Math.max(0, box.y - margin);
            int bottom = Math.min(page.getHeight(), box.y + box.height + margin);
            cropped.add(page.getSubimage(left, top, right - left, bottom - top));
        }
        return cropped;
    }

    /** Smallest rectangle holding every ink pixel of the page; {@code null} for a blank page. */
    static Rectangle inkBox(BufferedImage page) {
        int width = page.getWidth();
        int height = page.getHeight();
        int[] row = new int[width];
        int minX = width;
        int maxX = -1;
        int minY = height;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            page.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                if (isInk(row[x])) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static boolean isInk(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red < INK_THRESHOLD || green < INK_THRESHOLD || blue < INK_THRESHOLD;
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

    /** Scales down a picture that Telegram would refuse as a photo; a too narrow one gets white sides. */
    static BufferedImage fitTelegramLimits(BufferedImage image) {
        BufferedImage result = image;
        if (result.getHeight() > result.getWidth() * MAX_ASPECT_RATIO) {
            result = pad(result, (result.getHeight() + MAX_ASPECT_RATIO - 1) / MAX_ASPECT_RATIO);
        }
        int sum = result.getWidth() + result.getHeight();
        if (sum <= MAX_WIDTH_PLUS_HEIGHT) {
            return result;
        }
        double factor = (double) MAX_WIDTH_PLUS_HEIGHT / sum;
        int width = Math.max(1, (int) Math.floor(result.getWidth() * factor));
        int height = Math.max(1, (int) Math.floor(result.getHeight() * factor));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(result, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static BufferedImage pad(BufferedImage image, int width) {
        BufferedImage padded = new BufferedImage(width, image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = padded.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, image.getHeight());
            graphics.drawImage(image, (width - image.getWidth()) / 2, 0, null);
        } finally {
            graphics.dispose();
        }
        return padded;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new DocumentRenderingException("No PNG writer available");
        }
        return out.toByteArray();
    }
}
