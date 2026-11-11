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

    @Test
    void aPinnedHostIsCheckedAgainstTheCardsKeyAloneOnItsPort() throws Exception {
        final PoreloContext machine = context(dir);
        final String key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyPinnedByTheCard";
        final Path pin;
        final String understood;
        try (RemoteStart.Connection connection =
                RemoteStart.connection(
                        machine,
                        "person@build",
                        new OrganizationHostPin.Pin(
                                "build", "build.example.org", 2201, key, List.of("ada")))) {
            pin = java.util.Objects.requireNonNull(connection.pin());
            assertThat(Files.readString(pin))
                    .as("the card's key alone, under the alias ssh looks it up by")
                    .isEqualTo(RemoteStart.PINNED_ALIAS + " " + key + "\n");
            final List<String> asking = new ArrayList<>(List.of("ssh", "-G"));
            asking.addAll(connection.command().subList(1, connection.command().size()));
            final Process ssh = new ProcessBuilder(asking).redirectErrorStream(true).start();
            understood = new String(ssh.getInputStream().readAllBytes());
            ssh.waitFor();
        }

        assertThat(understood.lines())
                .as("no file of the person's can trust another key")
                .contains(
                        "userknownhostsfile " + pin,
                        "globalknownhostsfile /dev/null",
                        "hostkeyalias " + RemoteStart.PINNED_ALIAS,
                        "stricthostkeychecking true",
                        "hostkeyalgorithms ssh-ed25519",
                        "port 2201",
                        "hostname build.example.org",
                        "user person");
        assertThat(pin).as("the pin lives as long as the connection").doesNotExist();
    }
}
