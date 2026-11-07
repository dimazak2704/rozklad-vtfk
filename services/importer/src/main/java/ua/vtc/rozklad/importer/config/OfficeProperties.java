package ua.vtc.rozklad.importer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LibreOffice for DOC→DOCX conversion ({@code SOFFICE_PATH}): {@code soffice} in the container,
 * empty on the host ⇒ conversion is disabled gracefully.
 */
@ConfigurationProperties("app.office")
public record OfficeProperties(String sofficePath) {

    public boolean conversionEnabled() {
        return sofficePath != null && !sofficePath.isBlank();
    }
}
