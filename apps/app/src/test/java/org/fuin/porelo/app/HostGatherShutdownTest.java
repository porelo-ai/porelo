package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostGatherShutdownTest {
    @TempDir Path dir;

    @Test
    void signalingOnlyTheGatheredCommandStopsGitAndCleansItsClone() throws Exception {
        // Stopping the CLI must end Git and remove its private clone.
        final Path marker = dir.resolve("pids");
        final Path scratch = Files.createDirectory(dir.resolve("scratch"));
        final var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        HostGatherProcessChild.class.getName(),
                        "flow",
                        marker.toString(),
                        scratch.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.redirectOutput(dir.resolve("out").toFile());
        builder.redirectError(dir.resolve("err").toFile());
        final Process process = builder.start();
        long[] children = new long[0];
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(Files.exists(marker))
                    .as("child reached Git: " + Files.readString(dir.resolve("err")))
                    .isTrue();
            children =
                    java.util.Arrays.stream(Files.readString(marker).split(" "))
                            .mapToLong(Long::parseLong)
                            .toArray();
            process.destroy();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(dir.resolve("err")))
                    .doesNotContain("could not be read", "push failed", "Git was interrupted");
            final long stopped = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (java.util.Arrays.stream(children).anyMatch(HostGatherShutdownTest::alive)
                    && System.nanoTime() < stopped) Thread.sleep(10);
            for (long child : children)
                assertThat(alive(child)).as("git descendant " + child + " stopped").isFalse();
            try (var files = Files.list(scratch)) {
                assertThat(files.toList()).isEmpty();
            }
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            for (long child : children)
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
