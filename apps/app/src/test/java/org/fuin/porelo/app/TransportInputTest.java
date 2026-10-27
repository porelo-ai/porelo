package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how a transport is handed its secrets, with a stand-in transport that is really
 * started: what another process of the account reads in {@code /proc/<pid>/environ} is what the
 * stand-in finds in its own.
 */
class TransportInputTest {

    private static final String SECRET = "kept-for-the-transport-12345678";

    @TempDir Path dir;

    /** A transport that writes its own environment and its input where the test reads them. */
    private Path standIn() throws IOException {
        final Path script = dir.resolve("porelo-message-transport-room");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "tr '\\0' '\\n' < /proc/self/environ > \"$0.environ\"\n"
                        + "cat > \"$0.input\"\n"
                        + "echo '{}'\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private String run(final Path script, final TransportDescription described, final String verb)
            throws IOException {
        new ProcessCommandRunner(Duration.ofSeconds(20))
                .run(
                        HelperEnvironment.chosen(
                                TransportInput.handed(
                                        Command.of(script.toString(), verb),
                                        described,
                                        "setup".equals(verb) ? Map.of("homeserver", "h") : null,
                                        Map.of("PORELO_TOKEN", SECRET))));
        return Files.readString(Path.of(script + ".environ"));
    }

    @Test
    void aTransportTakingItsSecretsOnItsInputHasNoneInItsEnvironmentForAnyVerb() throws Exception {
        final Path script = standIn();
        final TransportDescription described =
                new TransportDescription(
                        "room",
                        true,
                        List.of(),
                        "read",
                        List.of("setup"),
                        List.of(TransportInput.INPUT),
                        "");

        for (final String verb : List.of("setup", "poll", "read", "receipt", "send")) {
            assertThat(run(script, described, verb))
                    .as("what any process of the account reads of %s", verb)
                    .doesNotContain(SECRET);
            assertThat(Files.readString(Path.of(script + ".input")))
                    .as("what %s read on its input", verb)
                    .contains("\"secrets\":{\"PORELO_TOKEN\":\"" + SECRET + "\"}");
        }
        assertThat(Files.readString(Path.of(script + ".input")))
                .as("a verb that takes no settings is given none")
                .doesNotContain("settings");
        run(script, described, "setup");
        assertThat(Files.readString(Path.of(script + ".input")))
                .isEqualTo(
                        "{\"settings\":{\"homeserver\":\"h\"},\"secrets\":{\"PORELO_TOKEN\":\""
                                + SECRET
                                + "\"}}");
    }

    @Test
    void aTransportThatDoesNotSaySoIsHandedThemAsBefore() throws Exception {

        // Its secrets in its environment and its settings alone on its input, until every
        // published transport takes them on its input.
        final Path script = standIn();
        final TransportDescription described =
                new TransportDescription("room", true, List.of(), "read", List.of("setup"));

        assertThat(run(script, described, "setup")).contains("PORELO_TOKEN=" + SECRET);
        assertThat(Files.readString(Path.of(script + ".input")))
                .isEqualTo("{\"homeserver\":\"h\"}");
    }

    @Test
    void aLifecycleVerbOfAProjectNamingNoSettingsIsGivenEmptySettingsOnTheInput() {
        final Command handed =
                TransportInput.handed(
                        Command.of("transport", "setup"),
                        new TransportDescription(
                                "room",
                                true,
                                List.of(),
                                "read",
                                List.of("setup"),
                                List.of(TransportInput.INPUT),
                                ""),
                        Map.of(),
                        Map.of());

        assertThat(handed.input()).isEqualTo("{\"settings\":{}}");
    }

    @Test
    void aTransportThatCouldNotBeAskedIsHandedNoSecretAtAll() {

        // Its describe failed, so how it takes them is unknown: in its environment they could be
        // read, so its verb runs without them and fails, rather than handing them out.
        final Command handed =
                TransportInput.handed(
                        Command.of("transport", "poll"),
                        TransportDescription.UNKNOWN,
                        null,
                        Map.of("PORELO_TOKEN", SECRET));

        assertThat(handed.environment()).isEmpty();
        assertThat(String.valueOf(handed.input())).doesNotContain(SECRET);
    }

    @Test
    void anOptionNamedLikeTheMarkerIsNoClaimToTakeSecretsOnTheInput() {
        final Command handed =
                TransportInput.handed(
                        Command.of("transport", "poll"),
                        new TransportDescription(
                                "room", true, List.of(), "read", List.of(), List.of("input"), ""),
                        null,
                        Map.of("PORELO_TOKEN", SECRET));

        assertThat(handed.environment()).containsEntry("PORELO_TOKEN", SECRET);
    }
}
