package ua.vtc.rozklad.importer.job;

import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Apply checkpoints kept in {@code import_job.result}: every finished step is stored at once. */
final class JobApplySteps implements ImportHandler.ApplySteps {

    private final UUID jobId;
    private final ObjectNode results;
    private final Consumer<JsonNode> store;

    JobApplySteps(UUID jobId, JsonNode previous, Consumer<JsonNode> store) {
        this.jobId = jobId;
        this.results = previous instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        this.store = store;
    }

    @Override
    public JsonNode done(String step) {
        JsonNode result = results.get(step);
        return result == null || result.isNull() ? null : result;
    }

    @Override
    public void finished(String step, JsonNode result) {
        results.set(step, result);
        store.accept(results.deepCopy());
    }

    @Override
    public String idempotencyKey(String step) {
        return "import-job:" + jobId + ":" + step;
    }

    @Override
    public JsonNode all() {
        return results.deepCopy();
    }
}
