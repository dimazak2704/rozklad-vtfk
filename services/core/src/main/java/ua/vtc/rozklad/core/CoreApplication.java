package ua.vtc.rozklad.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Domain owner of «Розклад ВТФК». Every direct sub-package is a Spring Modulith application
 * module; see {@code docs/ARCHITECTURE.md} §4 and {@code docs/modules/foundation.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
