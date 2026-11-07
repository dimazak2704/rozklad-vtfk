package ua.vtc.rozklad.importer.job;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Registry of {@link ImportHandler}s; exactly one per {@link ImportKind}. */
@Component
public class ImportHandlers {

    private final Map<ImportKind, ImportHandler> handlers = new EnumMap<>(ImportKind.class);

    public ImportHandlers(List<ImportHandler> all) {
        for (ImportHandler handler : all) {
            if (handlers.put(handler.kind(), handler) != null) {
                throw new IllegalStateException("Two import handlers for " + handler.kind());
            }
        }
        for (ImportKind kind : ImportKind.values()) {
            if (!handlers.containsKey(kind)) {
                throw new IllegalStateException("No import handler for " + kind);
            }
        }
    }

    public ImportHandler get(ImportKind kind) {
        return handlers.get(kind);
    }
}
