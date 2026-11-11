package ua.vtc.rozklad.auth.support;

import org.springframework.core.env.Environment;

/** Opens a fresh {@link Browser} (empty cookie jar) against the running server. */
public class Browsers {

    private final Environment environment;

    Browsers(Environment environment) {
        this.environment = environment;
    }

    public Browser open() {
        return new Browser("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"));
    }

    public Browser open(String clientIp) {
        Browser browser = open();
        browser.forwardedFor(clientIp);
        return browser;
    }
}
