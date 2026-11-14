package ua.vtc.rozklad.auth.oauth;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * At startup makes {@code oauth2_registered_client} equal to the configuration: upserts the clients of
 * {@link ClientRegistrations} (secrets re-encoded with {@link ClientSecrets}, so rotating a secret is "change env,
 * restart" and an older cost-12 hash becomes cost 4) and removes the others together with their authorizations,
 * e.g. {@code rozklad-cli} once {@code AUTH_CLI_ENABLED} is off.
 * Readiness turns UP only after runners, so no request meets a half-synchronised table.
 */
@Component
public class RegisteredClientSynchronizer implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RegisteredClientSynchronizer.class);

    private final RegisteredClientRepository repository;
    private final JdbcClient jdbc;
    private final AuthServerProperties properties;

    public RegisteredClientSynchronizer(RegisteredClientRepository repository, JdbcClient jdbc,
            AuthServerProperties properties) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        synchronise();
    }

    @Transactional
    public void synchronise() {
        List<RegisteredClient> clients = ClientRegistrations.build(properties, ClientSecrets.encoder()::encode);
        clients.forEach(repository::save);
        String[] ids = clients.stream().map(RegisteredClient::getId).toArray(String[]::new);
        int authorizations = jdbc.sql("DELETE FROM oauth2_authorization WHERE NOT (registered_client_id = ANY(?))")
                .param(ids).update();
        int removed = jdbc.sql("DELETE FROM oauth2_registered_client WHERE NOT (id = ANY(?))").param(ids).update();
        log.info("registered clients: {}{}", String.join(", ", ids),
                removed > 0 ? " (removed " + removed + " old, " + authorizations + " authorizations)" : "");
    }
}
