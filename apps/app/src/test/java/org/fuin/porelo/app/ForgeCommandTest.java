package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ForgeCommandTest {

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @Test
    void addsAForgeTrustsItsCommentsAndListsItAsTheDaemonDoes() throws IOException {
        final PoreloContext context = context();
        installed(context, "github");

        assertThat(execute(context, "forge", "add", "github.com", "--adapter", "github"))
                .as("added")
                .isZero();
        assertThat(execute(context, "forge", "comments", "github.com", "trusted"))
                .as("trusted")
                .isZero();
        out.getBuffer().setLength(0);
        assertThat(execute(context, "forge", "list", "--json")).as("listed").isZero();

        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString().strip());
        assertThat(answer.get("forges"))
                .as("the instance and its reading")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "host",
                                        "github.com",
                                        "adapter",
                                        "github",
                                        "comments",
                                        "TRUSTED")));
        // The packaged directory may hold adapters of this machine's own.
        assertThat(answer.get("adapters"))
                .as("the adapters installed")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("github");
        assertThat(context.paths().xdg().config().resolve(ForgeInstanceFile.NAME))
                .as("kept beside credentials.yml")
                .exists();
    }

    @Test
    void addingAgainMakesATrustedForgeExtended() throws IOException {
        final PoreloContext context = context();
        installed(context, "github");
        execute(context, "forge", "add", "github.com", "--adapter", "github");
        execute(context, "forge", "comments", "github.com", "trusted");

        execute(context, "forge", "add", "github.com", "--adapter", "github");
        out.getBuffer().setLength(0);
        execute(context, "forge", "list");

        assertThat(out.toString())
                .as("adding never loosens a reading")
                .contains("comments extended")
                .doesNotContain("comments trusted");
    }

    @Test
    void refusesAnAdapterNotInstalledAndAnUnknownForgesComments() throws IOException {
        final PoreloContext context = context();
        installed(context, "github");

        assertThat(execute(context, "forge", "add", "gitlab.com", "--adapter", "gitlab"))
                .as("an adapter not installed")
                .isEqualTo(2);
        assertThat(err.toString())
                .as("names it and what is installed")
                .contains("gitlab", "github");
        assertThat(execute(context, "forge", "comments", "codeberg.org", "trusted"))
                .as("a forge never added")
                .isEqualTo(2);
        assertThat(execute(context, "forge", "comments", "github.com", "perhaps"))
                .as("a reading that is neither")
                .isEqualTo(2);
        assertThat(context.paths().xdg().config().resolve(ForgeInstanceFile.NAME))
                .as("nothing written")
                .doesNotExist();
    }

    @Test
    void doctorNamesALoosenedAndAnUnreadableForgeFile() throws IOException {
        final Path file = dir.resolve(ForgeInstanceFile.NAME);
        final ForgeInstanceFile forges =
                new ForgeInstanceFile(file, new ForgeAdapters(List.of(dir.resolve("builds"))));

        assertThat(DoctorCommand.forges(forges).state())
                .as("no file is no forge, and fine")
                .isEqualTo(Probe.State.OK);

        Files.writeString(
                file,
                "forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        assertThat(DoctorCommand.forges(forges))
                .as("a file the group may write")
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).as("state").isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail())
                                    .as("names the file")
                                    .contains(file.toString());
                            assertThat(probe.action()).as("the mend").contains("chmod 600");
                        });

        Files.writeString(file, "forges: [unclosed\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(DoctorCommand.forges(forges))
                .as("a file that cannot be read")
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).as("state").isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail())
                                    .as("names the file")
                                    .contains(file.toString());
                        });
    }

    private int execute(final PoreloContext context, final String... arguments) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(arguments);
    }

    private static void installed(final PoreloContext context, final String... names)
            throws IOException {
        final Path builds = Files.createDirectories(context.paths().xdg().data().resolve("builds"));
        for (final String name : names) {
            final Path adapter = builds.resolve(name);
            Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
