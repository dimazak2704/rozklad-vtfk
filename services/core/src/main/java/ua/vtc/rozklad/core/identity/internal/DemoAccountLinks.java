package ua.vtc.rozklad.core.identity.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogImported;
import ua.vtc.rozklad.core.catalog.CatalogText;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.persistence.DemoSeedConfig;

/**
 * Demo seed (DOMAIN §12): links the demo Keycloak accounts {@code krentsin} and {@code nepyivoda}
 * (fixed ids from {@code infra/keycloak/rozklad-realm.json}) to the teachers «Кренцін» and
 * «Непийвода» once the Excel import has created them. Links only when exactly one unlinked teacher
 * has the surname and no teacher holds the account yet.
 */
@Component
@Profile(DemoSeedConfig.PROFILE)
class DemoAccountLinks {

    static final Map<String, UUID> ACCOUNTS = Map.of(
            "Кренцін", UUID.fromString("a0000000-0000-4000-8000-000000000002"),
            "Непийвода", UUID.fromString("a0000000-0000-4000-8000-000000000003"));

    private static final Logger log = LoggerFactory.getLogger(DemoAccountLinks.class);

    private final TeacherRepository teachers;

    DemoAccountLinks(TeacherRepository teachers) {
        this.teachers = teachers;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        link();
    }

    @ApplicationModuleListener
    public void on(CatalogImported imported) {
        link();
    }

    void link() {
        List<Teacher> all = teachers.findAll();
        ACCOUNTS.forEach((lastName, accountId) -> {
            if (all.stream().anyMatch(teacher -> accountId.equals(teacher.getKeycloakUserId()))) {
                return;
            }
            List<Teacher> candidates = all.stream()
                    .filter(teacher -> teacher.getKeycloakUserId() == null)
                    .filter(teacher -> CatalogText.key(teacher.getLastName()).equals(CatalogText.key(lastName)))
                    .toList();
            if (candidates.size() == 1) {
                candidates.getFirst().setKeycloakUserId(accountId);
                log.info("Demo seed: teacher {} linked to the demo account {}", candidates.getFirst().getShortName(),
                        accountId);
            }
        });
    }
}
