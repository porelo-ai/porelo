package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link UnixUpstream}: a socket taken only in the person's own runtime directory. */
class UnixUpstreamTest {

    @Test
    void takesASocketDirectlyInTheRuntimeDirectory(@TempDir Path dir) throws IOException {
        final Path runtime = dir.resolve("porelo");
        Files.createDirectories(runtime.resolve("mcp"));

        assertThat(UnixUpstream.checked("unix:" + runtime + "/mcp/task-1.sock", runtime))
                .isEqualTo(runtime.resolve("mcp/task-1.sock"));
    }

    @Test
    void takesItBeforeTheDirectoryExists(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertThat(UnixUpstream.checked("unix:" + runtime + "/mcp/task-1.sock", runtime))
                .isEqualTo(runtime.resolve("mcp/task-1.sock"));
    }

    @Test
    void refusesARelativePath(@TempDir Path dir) {
        assertRefused("unix:porelo/mcp/task-1.sock", dir, UnixUpstream.Refusal.RELATIVE);
        assertRefused("unix:", dir, UnixUpstream.Refusal.RELATIVE);
    }

    @Test
    void refusesAStepOut(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertRefused(
                "unix:" + runtime + "/mcp/../mcp/task-1.sock",
                runtime,
                UnixUpstream.Refusal.PARENT);
    }

    @Test
    void refusesAPathThatIsNotNormalized(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertRefused(
                "unix:" + runtime + "/mcp/./task-1.sock",
                runtime,
                UnixUpstream.Refusal.NOT_NORMALIZED);
        assertRefused(
                "unix:" + runtime + "/mcp//task-1.sock",
                runtime,
                UnixUpstream.Refusal.NOT_NORMALIZED);
        assertRefused(
                "unix:" + runtime + "/mcp/task-1.sock/",
                runtime,
                UnixUpstream.Refusal.NOT_NORMALIZED);
    }

    @Test
    void refusesACharacterThatCouldCarryMore(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertRefused(
                "unix:" + runtime + "/mcp/task|1.sock", runtime, UnixUpstream.Refusal.CHARACTER);
    }

    @Test
    void refusesAnotherDirectory(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertRefused(
                "unix:" + dir + "/elsewhere/task-1.sock", runtime, UnixUpstream.Refusal.OUTSIDE);
        assertRefused("unix:" + runtime + "/task-1.sock", runtime, UnixUpstream.Refusal.OUTSIDE);
        assertRefused(
                "unix:" + runtime + "/mcp/deeper/task-1.sock",
                runtime,
                UnixUpstream.Refusal.OUTSIDE);
        assertRefused("unix:" + runtime + "/mcp", runtime, UnixUpstream.Refusal.OUTSIDE);
    }

    @Test
    void refusesADirectoryThatIsALink(@TempDir Path dir) throws IOException {
        final Path runtime = dir.resolve("porelo");
        Files.createDirectories(runtime);
        final Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.createSymbolicLink(runtime.resolve("mcp"), elsewhere);

        assertRefused("unix:" + runtime + "/mcp/task-1.sock", runtime, UnixUpstream.Refusal.LINK);
    }

    @Test
    void refusesASocketThatIsALink(@TempDir Path dir) throws IOException {
        final Path runtime = dir.resolve("porelo");
        Files.createDirectories(runtime.resolve("mcp"));
        Files.createSymbolicLink(
                runtime.resolve("mcp/task-1.sock"), Files.createFile(dir.resolve("other.sock")));

        assertRefused("unix:" + runtime + "/mcp/task-1.sock", runtime, UnixUpstream.Refusal.LINK);
    }

    @Test
    void sendsEveryRequestToTheEndpointsPath() {
        assertThat(UnixUpstream.target("/")).isEqualTo("/mcp");
        assertThat(UnixUpstream.target("/?session=1")).isEqualTo("/mcp?session=1");
        assertThat(UnixUpstream.target("/sse")).isEqualTo("/mcp/sse");
    }

    private static void assertRefused(String upstream, Path runtime, UnixUpstream.Refusal reason) {
        assertThatThrownBy(() -> UnixUpstream.checked(upstream, runtime))
                .isInstanceOfSatisfying(
                        UnixUpstream.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
