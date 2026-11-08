package ua.vtc.rozklad.importer.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link CatalogPort} over core {@code GET /api/internal/catalog/snapshot} with the service-account token. */
@Component
public class CoreCatalogClient implements CatalogPort {

    static final String SNAPSHOT_PATH = "/api/internal/catalog/snapshot";

    private static final Logger log = LoggerFactory.getLogger(CoreCatalogClient.class);

    private final RestClient coreRestClient;

    public CoreCatalogClient(RestClient coreRestClient) {
        this.coreRestClient = coreRestClient;
    }

    @Override
    public CatalogSnapshot snapshot() {
        CatalogSnapshot snapshot;
        try {
            snapshot = coreRestClient.get().uri(SNAPSHOT_PATH).retrieve().body(CatalogSnapshot.class);
        } catch (RuntimeException e) {
            // RestClientException, а також помилка отримання сервісного токена (OAuth2AuthorizationException)
            log.warn("Catalog snapshot from core failed: {}", e.toString());
            throw new CatalogUnavailableException("Не вдалося отримати каталог із сервера розкладу.", e);
        }
        if (snapshot == null) {
            throw new CatalogUnavailableException("Сервер розкладу повернув порожню відповідь замість каталогу.", null);
        }
        return snapshot;
    }
}
