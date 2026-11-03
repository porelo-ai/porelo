package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A host's key the person confirmed through Porelo is the key a start to that host checks. */
class RemoteStartConnectionTest {

    @TempDir private Path dir;

    private PoreloContext context(final Path base) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> base.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> base.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> base.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> base.resolve("run").toString();
                                    default -> null;
                                },
                        base);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, base.resolve("bin")), arguments -> 0);
    }

    @Test
    void sshReadsPorelosRecordBesideItsOwnEvenFromAPathWithASpace() throws Exception {
        final PoreloContext machine = context(Files.createDirectory(dir.resolve("with space")));
        final List<String> connection = RemoteStart.connection(machine, "person@host.example");

        // Asks ssh itself how it understood the line, which connects nowhere.
        final List<String> asking = new ArrayList<>(List.of("ssh", "-G"));
        asking.addAll(connection.subList(1, connection.size()));
        final Process ssh = new ProcessBuilder(asking).redirectErrorStream(true).start();
        final String understood = new String(ssh.getInputStream().readAllBytes());
        ssh.waitFor();

        assertThat(understood.lines().filter(line -> line.startsWith("userknownhostsfile")))
                .singleElement()
                .asString()
                .contains(FollowCredential.knownHostsFile(machine).toString())
                .contains(".ssh/known_hosts");
    }
}
