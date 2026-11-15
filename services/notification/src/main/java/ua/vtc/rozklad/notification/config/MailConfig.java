package ua.vtc.rozklad.notification.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import ua.vtc.rozklad.notification.email.DisabledMailTransport;
import ua.vtc.rozklad.notification.email.MailChannel;
import ua.vtc.rozklad.notification.email.MailTransport;
import ua.vtc.rozklad.notification.email.SmtpMailTransport;

/**
 * Beans of the e-mail channel. The SMTP sender is built here, and only when the channel is on: the
 * {@code spring.mail.*} auto-configuration is not used, because an empty {@code spring.mail.host} would still
 * count as set and create a sender (and a mail health indicator that turns the service unready during an SMTP
 * outage; letters simply wait in the table then). Letter templates use a Thymeleaf engine of their own (SpEL
 * expressions, no Boot auto-configuration), so no view resolver appears in this REST service.
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    @Bean
    MailTransport mailTransport(MailChannel channel, Clock clock) {
        if (!channel.enabled()) {
            return new DisabledMailTransport();
        }
        return new SmtpMailTransport(javaMailSender(channel.properties()), channel, clock);
    }

    /** {@code mail.debug} is never switched on: it would print the SMTP conversation, AUTH included. */
    public static JavaMailSenderImpl javaMailSender(MailChannelProperties mail) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.host().strip());
        sender.setPort(mail.port());
        sender.setProtocol(mail.ssl() ? "smtps" : "smtp");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (mail.hasCredentials()) {
            sender.setUsername(mail.username());
            sender.setPassword(mail.password());
        }
        String prefix = mail.ssl() ? "mail.smtps." : "mail.smtp.";
        Properties properties = new Properties();
        properties.setProperty(prefix + "auth", Boolean.toString(mail.hasCredentials()));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(mail.starttls() && !mail.ssl()));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(mail.starttls() && !mail.ssl()));
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.setProperty(prefix + "connectiontimeout", Long.toString(mail.connectTimeout().toMillis()));
        properties.setProperty(prefix + "timeout", Long.toString(mail.readTimeout().toMillis()));
        properties.setProperty(prefix + "writetimeout", Long.toString(mail.writeTimeout().toMillis()));
        properties.setProperty("mail.debug", "false");
        sender.setJavaMailProperties(properties);
        return sender;
    }

    /**
     * {@code classpath:mail/*.html} in HTML mode ({@code th:text} escapes every value) and {@code mail/*.txt} in TEXT
     * mode (no markup, nothing to escape).
     */
    @Bean
    TemplateEngine mailTemplateEngine() {
        return templateEngine();
    }

    /** The engine of the letter templates (also for unit tests of the templates). */
    public static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.getHtmlTemplateModePatternSpec().addPattern("*.html");
        resolver.getTextTemplateModePatternSpec().addPattern("*.txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
