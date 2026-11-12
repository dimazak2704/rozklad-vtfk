package ua.vtc.rozklad.notification.events.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.events.DeadLetters;
import ua.vtc.rozklad.notification.web.ServiceUnavailableException;

/** Events that failed every retry ({@code notification.q.events.dlq}): how many, and replay after a fix. ADMIN. */
@RestController
@RequestMapping("/api/notify/events/dead-letters")
@Tag(name = "Необроблені події")
public class DeadLetterController {

    private static final String RABBIT_DOWN = "RabbitMQ недоступний, спробуйте пізніше.";

    private final DeadLetters deadLetters;

    public DeadLetterController(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    @Operation(summary = "Скільки подій не вдалося обробити (черга DLQ)")
    public DeadLettersDto count() {
        long count = deadLetters.check();
        if (count < 0) {
            throw new ServiceUnavailableException(RABBIT_DOWN);
        }
        return new DeadLettersDto(DeadLetters.deadLetterQueue(), count);
    }

    @PostMapping("/replay")
    @Operation(summary = "Повернути необроблені події на обробку (кожну один раз)")
    public ReplayDto replay() {
        int replayed = deadLetters.replay();
        if (replayed < 0) {
            throw new ServiceUnavailableException(RABBIT_DOWN);
        }
        return new ReplayDto(replayed);
    }

    public record DeadLettersDto(String queue, long count) {
    }

    public record ReplayDto(int replayed) {
    }
}
