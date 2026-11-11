package ua.vtc.rozklad.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import ua.vtc.rozklad.auth.account.AccountRepository;

/** Beans the ITs share: a way to create accounts and an HTTP client factory for the running server. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

    @Bean
    TestAccounts testAccounts(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        return new TestAccounts(accounts, passwordEncoder);
    }

    @Bean
    Browsers browsers(Environment environment) {
        return new Browsers(environment);
    }
}
