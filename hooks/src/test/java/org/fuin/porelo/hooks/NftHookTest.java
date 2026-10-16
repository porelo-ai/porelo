package org.fuin.porelo.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link NftHook}: a stage it does not know is a container that would start without its firewall.
 */
class NftHookTest {

    private int execute(final Path dir, final String... arguments) throws Exception {
        final Path sidecar = dir.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, "uc", "guarded", dir.resolve("ruleset.nft").toString(),
                dir.resolve("dns.conf").toString(), "/usr/bin/porelo", dir.toString()).writeTo(sidecar);
        final String state = "{\"ociVersion\":\"1.0.2\",\"id\":\"abc123\",\"status\":\"created\",\"pid\":4711,"
                + "\"annotations\":{\"" + Sidecar.ANNOTATION + "\":\"" + sidecar + "\"}}";
        return new NftHook().execute(arguments, new ByteArrayInputStream(state.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prestart", "createContainer"})
    void aStageItDoesNotLoadTheRulesetAtRefusesTheContainer(final String stage, @TempDir final Path dir)
            throws Exception {

        // Every stage but createRuntime was a successful nothing - also none at all - so a descriptor without the
        // stage, or with another, started the container with no ruleset.
        assertThat(stage.isEmpty() ? execute(dir) : execute(dir, stage)).isEqualTo(1);
    }

    @Test
    void anNftThatHangsIsStoppedAtTheHooksOwnLimitAndTheLogSaysSo(@TempDir final Path dir) throws Exception {

        // The runtime gives the hook 30 seconds and then kills it, which leaves no line saying why; the hook's own
        // limit is the one that has to hold first. The stand-in keeps its output open as a hung nft does.
        Files.writeString(dir.resolve("ruleset.nft"), "table inet porelo {}\n");
        final Path sidecar = dir.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, "uc", "guarded", dir.resolve("ruleset.nft").toString(),
                dir.resolve("dns.conf").toString(), "/usr/bin/porelo", dir.toString()).writeTo(sidecar);
        Sidecar.recordId(dir, "abc123abc123");
        final String state = "{\"ociVersion\":\"1.0.2\",\"id\":\"abc123abc123\",\"status\":\"created\",\"pid\":4711,"
                + "\"annotations\":{\"" + Sidecar.ANNOTATION + "\":\"" + sidecar + "\"}}";
        final NftHook hook = new NftHook(pid -> List.of("sh", "-c", "exec sleep 60"), Duration.ofSeconds(1));

        final int exit = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> hook.execute(
                new String[] {"createRuntime"}, new ByteArrayInputStream(state.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertThat(exit).as("the container is refused").isEqualTo(1);
        assertThat(Files.readString(dir.resolve("hooks.log"))).contains("createRuntime failed: ").contains("timed out");
    }

    @Test
    void theHooksLimitIsBelowTheRuntimes() {
        assertThat(NftHook.LIMIT).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void poststopHasNothingToUnload(@TempDir final Path dir) throws Exception {

        assertThat(execute(dir, "poststop")).isZero();
    }
}
