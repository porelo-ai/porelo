package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A window shows a host's keys and asks: looking records nothing, and confirming records one. */
class TrustHostJsonTest {

    private static final String ED25519 =
            "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    @TempDir private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        runner.answering("ssh-keyscan", ED25519 + "\n");
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int run(final String... args) {
        out.getBuffer().setLength(0);
        final CommandLine line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(new StringWriter()));
        return line.execute(args);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    private Map<?, ?> looked() {
        assertThat(run("credentials", "trust-host", "forge.example.org", "--json")).isZero();
        return answer();
    }

    @Test
    void lookingAnswersTheKeysAndRecordsNothing() {
        final Map<?, ?> first = looked();

        assertThat(first.get("host")).isEqualTo("forge.example.org");
        assertThat(first.get("known")).isEqualTo(false);
        final Map<?, ?> key = (Map<?, ?>) ((List<?>) first.get("keys")).getFirst();
        assertThat(key.get("type")).isEqualTo("ssh-ed25519");
        assertThat(String.valueOf(key.get("fingerprint"))).startsWith("SHA256:");
        assertThat(String.valueOf(key.get("key")))
                .startsWith("ssh-ed25519 AAAA")
                .doesNotContain("github");
        assertThat(looked().get("known")).as("still nothing recorded").isEqualTo(false);
    }

    @Test
    void confirmingRecordsTheKeyAndSaysWhich() {
        final String fingerprint =
                String.valueOf(
                        ((Map<?, ?>) ((List<?>) looked().get("keys")).getFirst())
                                .get("fingerprint"));

        assertThat(
                        run(
                                "credentials",
                                "trust-host",
                                "forge.example.org",
                                "--fingerprint",
                                fingerprint,
                                "--json"))
                .isZero();

        assertThat(((Map<?, ?>) answer().get("recorded")).get("fingerprint"))
                .isEqualTo(fingerprint);
        assertThat(looked().get("known")).isEqualTo(true);
    }

    @Test
    void aFingerprintTheHostDoesNotOfferRecordsNothing() {
        assertThat(
                        run(
                                "credentials",
                                "trust-host",
                                "forge.example.org",
                                "--fingerprint",
                                "SHA256:AAAA",
                                "--json"))
                .isEqualTo(70);

        assertThat(looked().get("known")).isEqualTo(false);
    }
}
