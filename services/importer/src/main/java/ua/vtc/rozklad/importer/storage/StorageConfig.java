package ua.vtc.rozklad.importer.storage;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ua.vtc.rozklad.importer.config.ImportProperties;

/** Chooses the file storage by {@code app.import.storage}. */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean
    FileStorage fileStorage(ImportProperties properties, ImportFileRepository files) {
        if (properties.storage() == ImportProperties.Storage.FILESYSTEM) {
            Path directory = properties.storageDir();
            if (directory == null) {
                throw new IllegalStateException("app.import.storage-dir must be set for FILESYSTEM storage");
            }
            return new FilesystemFileStorage(directory);
        }
        return new DatabaseFileStorage(files);
    }
}
