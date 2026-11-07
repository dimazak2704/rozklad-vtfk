package ua.vtc.rozklad.scheduler.generation;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * Consumer of {@code scheduler.q.generation}. A failing handler is retried by the container
 * (3 attempts); after that, and for unknown payloads, the message goes to the DLQ.
 */
@Component
@RabbitListener(queues = RozkladEvents.Queues.SCHEDULER_GENERATION)
public class GenerationEventsListener {

    private final GenerationIntake intake;

    public GenerationEventsListener(GenerationIntake intake) {
        this.intake = intake;
    }

    @RabbitHandler
    public void onRequested(GenerationRequested request) {
        intake.accept(request);
    }

    @RabbitHandler
    public void onCancel(GenerationCancelRequested cancel) {
        intake.cancel(cancel);
    }

    @RabbitHandler(isDefault = true)
    public void onUnexpected(Object payload) {
        throw new AmqpRejectAndDontRequeueException("Unexpected payload on " + RozkladEvents.Queues.SCHEDULER_GENERATION
                + ": " + payload.getClass().getName());
    }
}
