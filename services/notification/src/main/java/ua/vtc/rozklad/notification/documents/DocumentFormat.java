package ua.vtc.rozklad.notification.documents;

import java.util.Locale;
import java.util.Optional;
import org.springframework.http.MediaType;

public enum DocumentFormat {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", MediaType.APPLICATION_PDF_VALUE),
    PNG("png", MediaType.IMAGE_PNG_VALUE);

    private final String extension;
    private final String mediaType;

    DocumentFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return MediaType.parseMediaType(mediaType);
    }

    public static Optional<DocumentFormat> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.extension.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
