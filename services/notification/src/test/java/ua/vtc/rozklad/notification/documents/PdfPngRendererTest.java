package ua.vtc.rozklad.notification.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PdfPngRendererTest {

    private final PdfPngRenderer renderer = new PdfPngRenderer();

    @Test
    void rendersAtDoubleScaleAndStacksPages() throws IOException {
        BufferedImage onePage = ImageIO.read(new ByteArrayInputStream(renderer.render(pdf(1), 2)));
        assertThat(onePage.getWidth()).isEqualTo((int) (PDRectangle.A4.getWidth() * 2));
        assertThat(onePage.getHeight()).isEqualTo((int) (PDRectangle.A4.getHeight() * 2));

        BufferedImage twoPages = ImageIO.read(new ByteArrayInputStream(renderer.render(pdf(2), 2)));
        assertThat(twoPages.getWidth()).isEqualTo(onePage.getWidth());
        assertThat(twoPages.getHeight()).isEqualTo(onePage.getHeight() * 2 + 24);
    }

    @Test
    void refusesOrdersTooLongForAPicture() {
        assertThatThrownBy(() -> renderer.render(pdf(PdfPngRenderer.MAX_PAGES + 1), 2))
                .isInstanceOf(DocumentRenderingException.class).hasMessageContaining("pages");
        assertThatThrownBy(() -> renderer.render(new byte[] {1, 2, 3}, 2))
                .isInstanceOf(DocumentRenderingException.class);
    }

    static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
