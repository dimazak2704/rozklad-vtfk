package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.GenerationRequested;

/**
 * Opt-in (genfix contract): {@code -Dgenfix.snapshots=<dir>} with the real {@code generation.requested}
 * snapshots of the college. Their load fits the grid (2ТК: 18,5 pairs for 19 slots is the tightest), so the
 * pre-check (B9) must not refuse any of them. The snapshot files are not committed.
 */
@EnabledIfSystemProperty(named = "genfix.snapshots", matches = ".+")
class RealSnapshotLoadFitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<Path> snapshots() throws Exception {
        Path dir = Path.of(System.getProperty("genfix.snapshots"));
        return Stream.of("college-from-published.json", "ki4-scope-3groups.json", "course4-scope.json",
                "college-from-scratch.json").map(dir::resolve).filter(Files::exists);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snapshots")
    void theCollegeLoadFitsTheGrid(Path file) throws Exception {
        GenerationRequested request = JSON.readValue(Files.readString(file), GenerationRequested.class);

        assertThat(LoadFitCheck.check(request.problem()).problems()).isEmpty();
    }
}
