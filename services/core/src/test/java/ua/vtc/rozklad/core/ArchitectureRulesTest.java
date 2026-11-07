package ua.vtc.rozklad.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Code conventions every module must keep (ARCHITECTURE §8, §13). */
@AnalyzeClasses(packages = "ua.vtc.rozklad.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String[] MODULE_BASE_PACKAGES = {
            "ua.vtc.rozklad.core.catalog", "ua.vtc.rozklad.core.calendar", "ua.vtc.rozklad.core.load",
            "ua.vtc.rozklad.core.timetable", "ua.vtc.rozklad.core.substitution", "ua.vtc.rozklad.core.order",
            "ua.vtc.rozklad.core.accounting", "ua.vtc.rozklad.core.identity", "ua.vtc.rozklad.core.settings",
            "ua.vtc.rozklad.core.shared.."};

    @ArchTest
    static final ArchRule entitiesLiveInModuleBasePackages = classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAnyPackage(MODULE_BASE_PACKAGES)
            .because("other modules read entities through the module API (base package)");

    @ArchTest
    static final ArchRule repositoriesLiveInModuleBasePackages = classes().that().areInterfaces()
            .and().areAssignableTo(Repository.class)
            .should().resideInAnyPackage(MODULE_BASE_PACKAGES);

    @ArchTest
    static final ArchRule controllersAreModuleInternals = classes().that().areAnnotatedWith(RestController.class)
            .should().resideOutsideOfPackages("ua.vtc.rozklad.core.catalog", "ua.vtc.rozklad.core.calendar",
                    "ua.vtc.rozklad.core.load", "ua.vtc.rozklad.core.timetable", "ua.vtc.rozklad.core.substitution",
                    "ua.vtc.rozklad.core.order", "ua.vtc.rozklad.core.accounting", "ua.vtc.rozklad.core.identity",
                    "ua.vtc.rozklad.core.settings")
            .because("controllers are internals of a module (sub-packages), not its API");

    @ArchTest
    static final ArchRule noJackson2 = noClasses().should().dependOnClassesThat()
            .resideInAPackage("com.fasterxml.jackson.databind..")
            .because("Spring Boot 4 uses Jackson 3 (tools.jackson.*); only annotations stay in com.fasterxml");

    @ArchTest
    static final ArchRule timeComesFromClock = noClasses().should()
            .callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(Instant.class, "now")
            .because("inject java.time.Clock (Europe/Kyiv) so tests can fix the time");

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
