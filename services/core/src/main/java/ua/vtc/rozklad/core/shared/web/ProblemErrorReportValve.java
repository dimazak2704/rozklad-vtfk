package ua.vtc.rozklad.core.shared.web;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;

/**
 * Tomcat's error report for requests rejected before they reach Spring (an encoded slash {@code %2F},
 * {@code %00} in the path, a malformed request line): RFC 9457 ProblemDetail JSON instead of Tomcat's
 * HTML page, so every error of the API has one shape. Responses Spring has already written are untouched.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        try {
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body(status, request.getHeader("X-Request-Id")));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException ignored) {
            // клієнт пішов або відповідь уже закрита — писати нікуди
        }
    }

    static String body(int status, String requestId) {
        boolean badRequest = status == 400;
        String type = badRequest ? "bad-request" : "http-" + status;
        String title = badRequest ? "Некоректний запит" : "Помилка запиту";
        String detail = badRequest ? "Адреса запиту містить недопустимі символи або сегменти шляху."
                : "Сервер не зміг обробити запит.";
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"urn:rozklad:problem:").append(type)
                .append("\",\"title\":\"").append(title)
                .append("\",\"status\":").append(status)
                .append(",\"detail\":\"").append(detail).append('"');
        if (requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()) {
            json.append(",\"requestId\":\"").append(requestId).append('"');
        }
        return json.append('}').toString();
    }
}
