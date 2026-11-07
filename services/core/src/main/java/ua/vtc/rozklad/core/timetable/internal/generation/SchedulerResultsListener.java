package ua.vtc.rozklad.core.timetable.internal.generation;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * Consumes {@code core.q.scheduler-results}. The payload type is chosen by the routing key of the
 * contract (not by producer type headers); unreadable messages and unknown keys go to the DLQ.
 * Handling errors are retried by the listener container (3 attempts) and then dead-lettered too.
 */
@Component
class SchedulerResultsListener {

    private final GenerationResultHandler handler;
    private final JsonMapper jsonMapper;

    SchedulerResultsListener(GenerationResultHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)
    void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (RozkladEvents.GENERATION_PROGRESS.equals(routingKey)) {
            handler.onProgress(read(message, GenerationProgress.class));
        } else if (RozkladEvents.GENERATION_COMPLETED.equals(routingKey)) {
            handler.onCompleted(read(message, GenerationCompleted.class));
        } else {
            throw new AmqpRejectAndDontRequeueException("Unexpected routing key on scheduler results: " + routingKey);
        }
    }

    private <T> T read(Message message, Class<T> type) {
        try {
            return jsonMapper.readValue(message.getBody(), type);
        } catch (JacksonException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed " + type.getSimpleName() + " message", malformed);
        }
    }
}
