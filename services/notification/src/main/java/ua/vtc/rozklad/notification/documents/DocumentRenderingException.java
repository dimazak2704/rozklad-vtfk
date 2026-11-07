package ua.vtc.rozklad.notification.documents;

/** Rendering or conversion failed (LibreOffice error/timeout, broken PDF); may pass on retry. */
public class DocumentRenderingException extends RuntimeException {

    public DocumentRenderingException(String message) {
        super(message);
    }

    public DocumentRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
