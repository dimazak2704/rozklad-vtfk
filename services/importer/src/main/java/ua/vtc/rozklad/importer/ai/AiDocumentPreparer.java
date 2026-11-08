package ua.vtc.rozklad.importer.ai;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * Makes a document fit for a vision call before anything is paid for: the file must open (a damaged
 * JPEG or a «PDF» that is not one fails here with a plain message instead of the API's HTTP 400),
 * and an image over the API's limits — {@value #MAX_IMAGE_BYTES} bytes or {@value #MAX_API_SIDE} px
 * on a side — is downscaled to JPEG with its longest side {@value #TARGET_SIDE} px. Large images are
 * decoded with subsampling, so a 9000 px photo never needs a full-size bitmap in memory.
 */
final class AiDocumentPreparer {

    /** Claude API limit for one image. */
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** Claude API limit for an image side. */
    static final int MAX_API_SIDE = 8000;
    /** Longest side of a downscaled image (the API itself reads images at about this size). */
    static final int TARGET_SIDE = 2000;

    static final String DAMAGED_IMAGE = "Файл зображення пошкоджений або має непідтримуваний вміст — його не вдалося "
            + "прочитати. Збережіть фото ще раз (JPEG або PNG) і завантажте знову.";
    static final String DAMAGED_PDF = "PDF-файл пошкоджений — його не вдалося відкрити. Збережіть документ у PDF ще "
            + "раз і завантажте знову.";
    static final String PROTECTED_PDF = "PDF-файл захищено паролем — збережіть його без пароля й завантажте знову.";

    private AiDocumentPreparer() {
    }

    static AiDocument prepare(AiDocument document) {
        if (document.pdf()) {
            checkPdf(document);
            return document;
        }
        return prepareImage(document);
    }

    private static void checkPdf(AiDocument document) {
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            if (pdf.getNumberOfPages() == 0) {
                throw new AiExtractionException(DAMAGED_PDF);
            }
        } catch (InvalidPasswordException e) {
            throw new AiExtractionException(PROTECTED_PDF, e);
        } catch (IOException e) {
            throw new AiExtractionException(DAMAGED_PDF, e);
        }
    }

    private static AiDocument prepareImage(AiDocument document) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(document.content()))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                // форматів без декодера в JDK (WEBP) не перевіряємо: до ліміту — як є, понад — не зменшимо
                if (document.size() > MAX_IMAGE_BYTES) {
                    throw new AiExtractionException("Зображення більше 5 МБ і його не вдалося зменшити — збережіть його "
                            + "як JPEG меншого розміру.");
                }
                return document;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int longest = Math.max(width, height);
                ImageReadParam param = reader.getDefaultReadParam();
                // проріджування при декодуванні: не більше 2× цільового розміру в пам'яті
                int step = Math.max(1, (int) Math.ceil(longest / (2.0 * TARGET_SIDE)));
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (document.size() <= MAX_IMAGE_BYTES && longest <= MAX_API_SIDE) {
                    return document;
                }
                return downscaled(image);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof AiExtractionException extraction) {
                throw extraction;
            }
            throw new AiExtractionException(DAMAGED_IMAGE, e);
        }
    }

    private static AiDocument downscaled(BufferedImage image) throws IOException {
        double scale = Math.min(1.0, (double) TARGET_SIDE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, width, height, Color.WHITE, null);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpg", out);
        if (out.size() > MAX_IMAGE_BYTES) {
            throw new AiExtractionException("Зображення завелике навіть після зменшення — збережіть його меншим.");
        }
        return new AiDocument("image/jpeg", out.toByteArray());
    }
}
