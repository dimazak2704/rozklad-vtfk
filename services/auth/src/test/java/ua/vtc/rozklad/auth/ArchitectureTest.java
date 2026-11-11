package ua.vtc.rozklad.auth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The browser sign-in and the internal API stay independent (DESIGN §1.6). */
@AnalyzeClasses(packages = "ua.vtc.rozklad.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule internalApiDoesNotUseTheLoginPage = noClasses()
            .that().resideInAPackage("..auth.internal..")
            .should().dependOnClassesThat().resideInAPackage("..auth.login..");

    @ArchTest
    static final ArchRule loginDoesNotUseTheInternalApi = noClasses()
            .that().resideInAPackage("..auth.login..")
            .should().dependOnClassesThat().resideInAPackage("..auth.internal..");

    @ArchTest
    static final ArchRule accountsDependOnNothingAboveThem = noClasses()
            .that().resideInAPackage("..auth.account..")
            .should().dependOnClassesThat().resideInAnyPackage("..auth.login..", "..auth.oauth..", "..auth.internal..",
                    "..auth.seed..");
}
