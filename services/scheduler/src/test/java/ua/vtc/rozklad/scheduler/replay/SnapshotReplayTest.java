package ua.vtc.rozklad.scheduler.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Replays real generation requests (and synthetic colleges) with the production solver configuration and logs
 * the genRun metric table. Opt-in: tagged {@code replay}, which the scheduler build excludes by default, and it
 * needs {@code -Dreplay=<datasets>}:
 *
 * <pre>
 * mvn -pl services/scheduler -am test -Dtest=SnapshotReplayTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dscheduler.excludedTags=none -Dreplay=college-from-published:180,ki4-scope-3groups:60 \
 *     [-Dreplay.weights=stability=2,disciplineTwice=3] [-Dreplay.label=A] [-Dreplay.out=results.jsonl] \
 *     [-Dreplay.termination=spent|auto] [-Dreplay.seed=1]
 * </pre>
 *
 * Datasets: {@code college-from-published}, {@code ki4-scope-3groups}, {@code course4-scope},
 * {@code college-from-scratch} (gzipped in {@code src/test/resources/replay}), a JSON file path, or
 * {@code synthetic:<shape>[@warm]} with the shapes of {@code ShapedCollege}. For long batches run
 * {@link SnapshotReplay#main} directly on the test classpath.
 */
@Tag("replay")
@EnabledIfSystemProperty(named = "replay", matches = ".+")
class SnapshotReplayTest {

    @Test
    void replaysTheRequestedDatasets() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("datasets", System.getProperty("replay"));
        copy(options, "weights", "replay.weights");
        copy(options, "label", "replay.label");
        copy(options, "out", "replay.out");
        copy(options, "termination", "replay.termination");
        copy(options, "newCore", "replay.newCore");
        copy(options, "seed", "replay.seed");

        List<SnapshotReplay.Run> runs = SnapshotReplay.runAll(options);

        assertThat(runs).isNotEmpty().allSatisfy(run -> {
            assertThat(run.last().metrics().get("unplaced")).as("unplaced units of %s", run.dataset()).isZero();
            assertThat(run.last().score()).as("final score of %s", run.dataset()).isNotNull();
        });
    }

    private static void copy(Map<String, String> options, String key, String property) {
        String value = System.getProperty(property);
        if (value != null) {
            options.put(key, value);
        }
    }
}
