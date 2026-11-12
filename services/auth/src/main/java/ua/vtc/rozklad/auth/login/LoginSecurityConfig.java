package ua.vtc.rozklad.auth.login;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import ua.vtc.rozklad.auth.account.AccountUserDetailsService;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.google.GoogleLogin;
import ua.vtc.rozklad.auth.oauth.ClientRegistrations;

/**
 * The browser side of {@code auth} (filter chain 3 of DESIGN §4.1): the sign-in page, form login and Google,
 * with CSRF, a new session id on sign-in, the lockout filter and a strict CSP. Everything that is not a
 * protocol or internal endpoint lands here.
 */
@Configuration(proxyBeanMethods = false)
public class LoginSecurityConfig {

    @Bean
    @Order(3)
    SecurityFilterChain loginChain(HttpSecurity http, AccountUserDetailsService users, PasswordEncoder passwordEncoder,
            LoginOutcomeHandlers outcomes, LoginAttempts attempts, LoginDevices devices, AuthServerProperties properties,
            Clock clock, GoogleLogin google) throws Exception {
        // Google (якщо налаштовано): /oauth2/authorization/google → Google → /login/oauth2/code/google
        google.configure(http);
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/login", "/ui/**", "/error", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .authenticationManager(new ProviderManager(providers(passwordProvider(users, passwordEncoder), google)))
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(outcomes.successHandler())
                        .failureHandler(outcomes.failureHandler())
                        .permitAll())
                .addFilterBefore(new LoginThrottleFilter(attempts, devices, properties.lockout(), clock),
                        UsernamePasswordAuthenticationFilter.class)
                // вихід — лише через OIDC /connect/logout (з id_token_hint), щоб видалялась і авторизація клієнта
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(LoginPagePolicy.contentSecurityPolicy(
                                ClientRegistrations.redirectOrigins(properties))))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    /** The password provider and, when Google is configured, its provider; no parent manager. */
    static List<AuthenticationProvider> providers(DaoAuthenticationProvider password, GoogleLogin google) {
        List<AuthenticationProvider> providers = new ArrayList<>();
        providers.add(password);
        providers.addAll(google.authenticationProviders());
        return providers;
    }

    /**
     * BCrypt check first, account status after: a disabled account is reported only to someone who knows its
     * password, and the timing of a wrong password is the same for every account.
     */
    static DaoAuthenticationProvider passwordProvider(AccountUserDetailsService users, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return provider;
    }
}
