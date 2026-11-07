package ua.vtc.rozklad.importer.catalog;

/** Source of the core catalog used for normalization (core REST in production, a stub in tests). */
public interface CatalogPort {

    /**
     * @throws CatalogUnavailableException when core cannot be reached or answers with an error
     */
    CatalogSnapshot snapshot();
}
