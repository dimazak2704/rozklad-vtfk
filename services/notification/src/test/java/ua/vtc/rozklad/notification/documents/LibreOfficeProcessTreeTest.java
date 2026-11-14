package ua.vtc.rozklad.notification.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code soffice} is a launcher: the process Java started is not the one that converts. A timed-out conversion
 * must not leave {@code soffice.bin} running with the shared profile.
 */
class LibreOfficeProcessTreeTest {

    private final List<ProcessHandle> started = new ArrayList<>();

    @AfterEach
    void killLeftovers() {
        started.forEach(ProcessHandle::destroyForcibly);
    }

    @Test
    void aTimedOutLauncherIsKilledWithTheProcessItStarted() throws Exception {
        Process launcher = launcherWithChild();
        ProcessHandle child = awaitChild(launcher);

        LibreOfficeConverter.destroyTree(launcher);

        await(() -> !launcher.isAlive() && !child.isAlive());
        assertThat(child.isAlive()).as("the converter the launcher started").isFalse();
    }

    @Test
    void destroyTreeOfAFinishedProcessIsHarmless() throws Exception {
        Process done = new ProcessBuilder(java(), "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        done.waitFor();

        LibreOfficeConverter.destroyTree(done);

        assertThat(done.isAlive()).isFalse();
    }

    /** Like {@code soffice} → {@code soffice.bin}: a process that starts a long-running child and waits. */
    private Process launcherWithChild() throws IOException {
        Process launcher = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"),
                SleepingTree.class.getName(), "launcher")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        started.add(launcher.toHandle());
        return launcher;
    }

    /** The JVM the launcher started (on Windows a console host process is a descendant too). */
    private ProcessHandle awaitChild(Process launcher) {
        await(() -> javaChild(launcher).isPresent());
        ProcessHandle child = javaChild(launcher).orElseThrow();
        started.add(child);
        return child;
    }

    private static Optional<ProcessHandle> javaChild(Process launcher) {
        return launcher.descendants()
                .filter(handle -> handle.info().command().map(command -> command.equals(java())).orElse(false))
                .findFirst();
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached in 20 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** {@code launcher}: starts {@code converter} and sleeps; {@code converter}: sleeps. Both for a minute. */
    public static final class SleepingTree {

        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("launcher")) {
                new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), SleepingTree.class.getName(),
                        "converter").inheritIO().start();
            }
            Thread.sleep(60_000);
        }
    }
}
