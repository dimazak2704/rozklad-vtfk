package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/** JSON columns of {@link GenerationJob} ↔ typed values, and the job → {@link GenerationJobDto} mapping. */
@Component
class GenerationJobMapper {

    static final int MAX_PROGRESS_POINTS = 1000;

    private final JsonMapper jsonMapper;

    GenerationJobMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    GenerationJobDto toDto(GenerationJob job) {
        JsonNode timeLimit = job.getParams().path("timeLimitSeconds");
        return new GenerationJobDto(job.getId(), job.getSemesterId(), job.getStatus(), job.getCreatedAt(),
                job.getStartedAt(), job.getFinishedAt(), job.getBestScore(), progress(job.getProgress()),
                job.getResultVersionId(), job.getBaseVersionId(), timeLimit.isNumber() ? timeLimit.asInt() : null,
                job.getAnalysis() == null ? null : Arrays.asList(jsonMapper.treeToValue(job.getAnalysis(),
                        ConstraintScore[].class)),
                metrics(job.getMetricsBefore()), metrics(job.getMetricsAfter()), job.getError(),
                job.getRequestedBy());
    }

    JsonNode json(Object value) {
        return value == null ? null : jsonMapper.valueToTree(value);
    }

    GenerationPlan plan(GenerationJob job) {
        return jsonMapper.treeToValue(job.getPlan(), GenerationPlan.class);
    }

    MetricsDto metrics(JsonNode node) {
        return node == null || node.isNull() ? null : jsonMapper.treeToValue(node, MetricsDto.class);
    }

    /**
     * Progress points with the new one added in time order; a new array (dirty checking compares
     * values). Beyond {@value #MAX_PROGRESS_POINTS} points new ones are dropped.
     */
    JsonNode withProgressPoint(JsonNode current, double seconds, long hard, long soft) {
        ArrayNode points = jsonMapper.createArrayNode();
        if (current != null && current.isArray()) {
            current.forEach(points::add);
        }
        if (points.size() >= MAX_PROGRESS_POINTS) {
            return points;
        }
        ObjectNode point = jsonMapper.createObjectNode();
        point.put("t", seconds);
        point.put("hard", hard);
        point.put("soft", soft);
        int index = 0;
        while (index < points.size() && points.get(index).path("t").asDouble() <= seconds) {
            index++;
        }
        points.insert(index, point);
        return points;
    }

    private List<GenerationJobDto.ProgressPoint> progress(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return Arrays.asList(jsonMapper.treeToValue(node, GenerationJobDto.ProgressPoint[].class));
    }

    /** Timefold-style score text, e.g. {@code 0hard/-42soft}. */
    static String score(long hard, long soft) {
        return hard + "hard/" + soft + "soft";
    }
}
