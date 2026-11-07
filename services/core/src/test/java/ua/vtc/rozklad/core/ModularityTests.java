package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries of core (ARCHITECTURE §4): no cycles, no access to other modules' internals. */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(CoreApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void everyPlannedModuleIsDetected() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("shared", "catalog", "calendar", "load", "timetable", "substitution",
                        "order", "accounting", "identity", "settings");
    }

    @Test
    void writesModuleDocumentation() {
        String outputFolder = System.getProperty("modulith.docs.dir", "target/spring-modulith-docs");
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(outputFolder))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
