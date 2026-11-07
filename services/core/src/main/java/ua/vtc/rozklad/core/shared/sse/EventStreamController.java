package ua.vtc.rozklad.core.shared.sse;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** {@code GET /api/events/stream}: realtime events for ADMIN and TEACHER sessions. */
@RestController
@RequestMapping("/api/events")
class EventStreamController {

    private final SseBroadcaster broadcaster;

    EventStreamController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(CurrentUser user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        // nginx не буферизує потік, інакше події приходять пачками
        response.setHeader("X-Accel-Buffering", "no");
        return broadcaster.subscribe(user);
    }
}
