package ua.vtc.rozklad.importer.catalog;

/** The core catalog could not be fetched (core down, auth problem, invalid answer). */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
