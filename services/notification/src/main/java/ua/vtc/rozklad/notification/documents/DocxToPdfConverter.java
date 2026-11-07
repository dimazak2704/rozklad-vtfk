package ua.vtc.rozklad.notification.documents;

/** DOCX → PDF. Implemented with LibreOffice headless; tests use a PDFBox-made stand-in. */
public interface DocxToPdfConverter {

    boolean isAvailable();

    /**
     * @throws ConverterUnavailableException no converter on this host
     * @throws DocumentRenderingException    the conversion failed or timed out
     */
    byte[] toPdf(byte[] docx);
}
