package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/**
 * JSON columns of {@link GenerationJob} ↔ typed values, and the job → {@link GenerationJobDto} mapping.
 * Old JSON reads through the additive records: weights of the five-field schema take the new defaults,
 * metrics without the genfix fields read them as {@code null}. The groups of a list of jobs are read with
 * one query.
 */
@Component
class GenerationJobMapper {

    static final int MAX_PROGRESS_POINTS = 1000;
    private static final Pattern SCORE = Pattern.compile("(-?\\d+)hard/(-?\\d+)soft");
    private static final Comparator<GroupRef> GROUP_ORDER = Comparator.comparingInt(GroupRef::course)
            .thenComparing(GroupRef::code);

    private final JsonMapper jsonMapper;
    private final StudentGroupRepository groups;

    GenerationJobMapper(JsonMapper jsonMapper, StudentGroupRepository groups) {
        this.jsonMapper = jsonMapper;
        this.groups = groups;
    }

    GenerationJobDto toDto(GenerationJob job) {
        return toDtos(List.of(job)).getFirst();
    }

    /** Jobs in the given order; the groups of all of them come from one batch query. */
    List<GenerationJobDto> toDtos(List<GenerationJob> jobs) {
        Set<UUID> groupIds = new HashSet<>();
        jobs.forEach(job -> groupIds.addAll(groupIds(job)));
        Map<UUID, GroupRef> refs = new HashMap<>();
        if (!groupIds.isEmpty()) {
            groups.findAllById(groupIds).forEach(group -> refs.put(group.getId(), GroupRef.of(group)));
        }
        return jobs.stream().map(job -> toDto(job, refs)).toList();
    }

    private GenerationJobDto toDto(GenerationJob job, Map<UUID, GroupRef> refs) {
        JsonNode params = job.getParams();
        JsonNode timeLimit = params.path("timeLimitSeconds");
        Long[] score = parseScore(job.getBestScore());
        List<GroupRef> jobGroups = groupIds(job).stream().map(refs::get).filter(java.util.Objects::nonNull)
                .sorted(GROUP_ORDER).toList();
        return new GenerationJobDto(job.getId(), job.getSemesterId(), job.getStatus(), job.getCreatedAt(),
                job.getStartedAt(), job.getFinishedAt(), job.getBestScore(), progress(job.getProgress()),
                job.getResultVersionId(), job.getBaseVersionId(), timeLimit.isNumber() ? timeLimit.asInt() : null,
                job.getAnalysis() == null ? null : Arrays.asList(jsonMapper.treeToValue(job.getAnalysis(),
                        ConstraintScore[].class)),
                metrics(job.getMetricsBefore()), metrics(job.getMetricsAfter()), job.getError(),
                job.getRequestedBy(), scope(params), jobGroups, weights(params), score[0], score[1],
                outcome(job.getOutcome()), legacyWeights(params), job.getStopRequestedAt() != null,
                job.getStopReason(), job.getBaseHardScore(), job.getBaseSoftScore());
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

    GenerationOutcome outcome(JsonNode node) {
        return node == null || node.isNull() ? null : jsonMapper.treeToValue(node, GenerationOutcome.class);
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

    /** The resolved scope ({@code params.groupIds}). */
    private static Collection<UUID> groupIds(GenerationJob job) {
        JsonNode ids = job.getParams().path("groupIds");
        List<UUID> result = new ArrayList<>();
        if (ids.isArray()) {
            ids.forEach(id -> result.add(UUID.fromString(id.asString())));
        }
        return result;
    }

    private GenerationJobDto.Scope scope(JsonNode params) {
        JsonNode scope = params.path("scope");
        return scope.isObject() ? jsonMapper.treeToValue(scope, GenerationJobDto.Scope.class) : null;
    }

    /**
     * Whether the stored weights are the five of the first schema (no {@code teacherWindows}): that run had none of
     * the genfix rules, so the history must not present their defaults as what it used. {@code null}: no weights.
     */
    private static Boolean legacyWeights(JsonNode params) {
        JsonNode weights = params.path("weights");
        return weights.isObject() ? !weights.has("teacherWindows") : null;
    }

    /** The stored weights through the record: a job of the five-weight schema reads with the new defaults. */
    private GenerationWeights weights(JsonNode params) {
        JsonNode weights = params.path("weights");
        return weights.isObject() ? jsonMapper.treeToValue(weights, GenerationWeights.class) : null;
    }

    /** {@code [hard, soft]} of a Timefold-style score text, {@code [null, null]} when there is none. */
    static Long[] parseScore(String score) {
        Matcher matcher = score == null ? null : SCORE.matcher(score);
        if (matcher == null || !matcher.matches()) {
            return new Long[] {null, null};
        }
        return new Long[] {Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
    }

    /** Timefold-style score text, e.g. {@code 0hard/-42soft}. */
    static String score(long hard, long soft) {
        return hard + "hard/" + soft + "soft";
    }
}
