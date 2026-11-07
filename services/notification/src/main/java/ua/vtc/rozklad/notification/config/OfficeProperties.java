package ua.vtc.rozklad.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LibreOffice for DOCX→PDF rendering ({@code SOFFICE_PATH}): {@code soffice} in the container,
 * empty on the host ⇒ PDF/PNG rendering is disabled gracefully (DOCX still works).
 */
@ConfigurationProperties("app.office")
public record OfficeProperties(String sofficePath) {

    public boolean conversionEnabled() {
        return sofficePath != null && !sofficePath.isBlank();
    }
}
