package ua.vtc.rozklad.auth.login;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * The Ukrainian sign-in page ({@code GET /auth/login}). The form posts to {@code /auth/login}; Spring
 * Security handles the post. Error codes in the query string map to one fixed message each, so nothing a
 * visitor types is ever echoed back.
 */
@Controller
public class LoginController {

    static final Map<String, String> MESSAGES = Map.of(
            "credentials", "Неправильний логін або пароль.",
            "throttled", "Забагато спроб. Спробуйте пізніше або увійдіть через Google.",
            "throttled_password_only", "Забагато спроб. Спробуйте пізніше.",
            "not_allowed", "Цієї адреси немає у списку доступу. Зверніться до навчальної частини.",
            "google", "Не вдалося увійти через Google. Спробуйте ще раз.");

    /** Google sign-in arrives with its own configuration; until then the page shows the password form only. */
    private static final boolean GOOGLE_ENABLED = false;

    private final AuthServerProperties properties;

    public LoginController(AuthServerProperties properties) {
        this.properties = properties;
    }

    /** {@code /auth/} has nothing of its own: show the sign-in page. */
    @GetMapping("/")
    String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    String login(@RequestParam(name = "error", required = false) String error, Authentication authentication,
            Model model) {
        if (error == null && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            // уже увійшли: сайт сам завершить вхід через SSO
            return "redirect:" + publicWebUrl() + "/login";
        }
        model.addAttribute("message", message(error, GOOGLE_ENABLED));
        model.addAttribute("passwordError", error != null && !"google".equals(error) && !"not_allowed".equals(error));
        model.addAttribute("googleEnabled", GOOGLE_ENABLED);
        model.addAttribute("publicWebUrl", publicWebUrl());
        return "login";
    }

    /**
     * {@code ?error} without a value is a wrong password; unknown codes show the generic text too. The
     * throttled text points to Google only when Google sign-in exists.
     */
    static String message(String error, boolean googleEnabled) {
        if (error == null) {
            return null;
        }
        String code = error.isEmpty() || "throttled_password_only".equals(error) ? "credentials" : error;
        if ("throttled".equals(code) && !googleEnabled) {
            code = "throttled_password_only";
        }
        return MESSAGES.getOrDefault(code, MESSAGES.get("credentials"));
    }

    private String publicWebUrl() {
        String url = properties.publicWebUrl().toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
