package ua.vtc.rozklad.notification.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PdfPngRendererTest {

    private static final float A4_HEIGHT = PDRectangle.A4.getHeight();

    private final PdfPngRenderer renderer = new PdfPngRenderer();

    @Test
    void shortOrderIsCroppedToItsContentWithAMargin() throws IOException {
        // таблиця 400×100 pt угорі аркуша A4, решта сторінки порожня
        BufferedImage picture = image(renderer.render(pdf(box(60, 50, 400, 100)), 2));

        int margin = PdfPngRenderer.MARGIN * 2;
        assertThat(picture.getWidth()).isBetween(800 + 2 * margin - 2, 800 + 2 * margin + 2);
        assertThat(picture.getHeight()).isBetween(200 + 2 * margin - 2, 200 + 2 * margin + 2);
        assertThat(picture.getHeight()).as("not an A4 sheet").isLessThan((int) A4_HEIGHT);
        // поле біле, вміст чорний
        assertThat(picture.getRGB(2, 2) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(picture.getRGB(picture.getWidth() / 2, picture.getHeight() / 2) & 0xFFFFFF).isZero();
    }

    @Test
    void pagesShareOneColumnRangeAndAreStacked() throws IOException {
        BufferedImage picture = image(renderer.render(pdf(box(60, 50, 400, 100), box(100, 50, 200, 300)), 2));

        int margin = PdfPngRenderer.MARGIN * 2;
        // спільна ширина: від x=60 до x=460 pt на обох сторінках
        assertThat(picture.getWidth()).isBetween(800 + 2 * margin - 2, 800 + 2 * margin + 2);
        assertThat(picture.getHeight()).isBetween(200 + 600 + 4 * margin + 24 - 4, 200 + 600 + 4 * margin + 24 + 4);
    }

    @Test
    void blankDocumentKeepsThePage() throws IOException {
        BufferedImage picture = image(renderer.render(pdf(new int[0]), 2));
        assertThat(picture.getWidth()).isEqualTo((int) (PDRectangle.A4.getWidth() * 2));
        assertThat(picture.getHeight()).isEqualTo((int) (A4_HEIGHT * 2));
    }

    @Test
    void picturesStayWithinTelegramPhotoLimits() {
        BufferedImage tall = new BufferedImage(1200, 9500, BufferedImage.TYPE_INT_RGB);
        BufferedImage fitted = PdfPngRenderer.fitTelegramLimits(tall);
        assertThat(fitted.getWidth() + fitted.getHeight()).isLessThanOrEqualTo(PdfPngRenderer.MAX_WIDTH_PLUS_HEIGHT);
        assertThat((double) fitted.getHeight() / fitted.getWidth()).isCloseTo(9500 / 1200.0,
                org.assertj.core.data.Offset.offset(0.02));

        BufferedImage strip = new BufferedImage(100, 4000, BufferedImage.TYPE_INT_RGB);
        BufferedImage padded = PdfPngRenderer.fitTelegramLimits(strip);
        assertThat(padded.getHeight()).isLessThanOrEqualTo(padded.getWidth() * PdfPngRenderer.MAX_ASPECT_RATIO);

        BufferedImage normal = new BufferedImage(1100, 700, BufferedImage.TYPE_INT_RGB);
        assertThat(PdfPngRenderer.fitTelegramLimits(normal)).isSameAs(normal);
    }

    @Test
    void inkBoxFindsPrintedPixels() {
        BufferedImage page = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 80);
        assertThat(PdfPngRenderer.inkBox(page)).isNull();
        graphics.setColor(Color.GRAY);
        graphics.fillRect(10, 20, 30, 5);
        graphics.dispose();
        assertThat(PdfPngRenderer.inkBox(page)).isEqualTo(new java.awt.Rectangle(10, 20, 30, 5));
    }

    @Test
    void refusesOrdersTooLongForAPicture() {
        assertThatThrownBy(() -> renderer.render(pdf(PdfPngRenderer.MAX_PAGES + 1), 2))
                .isInstanceOf(DocumentRenderingException.class).hasMessageContaining("pages");
        assertThatThrownBy(() -> renderer.render(new byte[] {1, 2, 3}, 2))
                .isInstanceOf(DocumentRenderingException.class);
    }

    /** Blank A4 pages. */
    static byte[] pdf(int pages) throws IOException {
        int[][] content = new int[pages][];
        for (int i = 0; i < pages; i++) {
            content[i] = new int[0];
        }
        return pdf(content);
    }

    /** A4 pages, each with one black box {@code {x, yFromTop, width, height}} (or blank for an empty array). */
    private static byte[] pdf(int[]... boxes) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int[] box : boxes) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                if (box.length == 4) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.setNonStrokingColor(Color.BLACK);
                        stream.addRect(box[0], A4_HEIGHT - box[1] - box[3], box[2], box[3]);
                        stream.fill();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static int[] box(int x, int yFromTop, int width, int height) {
        return new int[] {x, yFromTop, width, height};
    }

    private static BufferedImage image(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
