package ua.vtc.rozklad.importer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Import of the college Excel, discipline programs, curricula and order documents/photos:
 * deterministic parsers first, AI extraction as fallback, normalization against the core catalog;
 * applies reviewed drafts through core REST only.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ImporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImporterApplication.class, args);
    }
}
