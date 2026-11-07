package ua.vtc.rozklad.notification.documents;

/** No LibreOffice ({@code SOFFICE_PATH} empty or the binary cannot be started): PDF/PNG are impossible. */
public class ConverterUnavailableException extends RuntimeException {

    public static final String MESSAGE = "Конвертер PDF недоступний";

    public ConverterUnavailableException(String detail) {
        super(MESSAGE + (detail == null ? "" : ": " + detail));
    }
}
