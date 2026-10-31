package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@code porelo task certify}: only a task with a key of its own is certified anew. */
class TaskCertifyCommandTest {

    @TempDir Path dir;

    @Test
    void aTaskWithNoKeyOfItsOwnIsNotCertifiedAndSaysWhy() {
        final StringWriter err = new StringWriter();
        final CommandLine cli = cli(err);

        assertThat(cli.execute("task", "certify", "porelo-uc-fix")).isEqualTo(66);
        assertThat(err.toString()).contains("no key of its own");
    }

    @Test
    void aCertificateOnAHostNamesTheProjectAndAHostSshTakesAsNoOption() {
        // Without the project, a host could ask for another project's task; a host or login that
        // begins with a dash is one of ssh's options.
        final String host = "porelo: --host takes <login>@<host>";
        final String project =
                "porelo: --host needs --project: the certificate names the task's project";
        final String own = "porelo: --host needs the task's own name, not its container's";
        for (final String[] given :
                new String[][] {
                    {project, "fix", "--host=a@h"},
                    {project, "fix", "--host=a@h", "--project="},
                    {host, "fix", "--host=-oProxyCommand@h", "-p", "uc"},
                    {own, "porelo-uc-fix", "--host=a@h", "-p", "uc"},
                    {
                        "porelo: --host needs a project's name, [a-z0-9][a-z0-9-]{0,62}",
                        "fix",
                        "--host=a@h",
                        "-p",
                        "a,root"
                    }
                }) {
            final StringWriter err = new StringWriter();
            final String[] args = new String[given.length + 1];
            args[0] = "task";
            args[1] = "certify";
            System.arraycopy(given, 1, args, 2, given.length - 1);

            assertThat(cli(err).execute(args)).as(String.join(" ", args)).isEqualTo(64);
            assertThat(err.toString().strip()).as(String.join(" ", args)).isEqualTo(given[0]);
        }
    }

    @Test
    void aProjectNamedPicksItsOwnTaskAndNoOtherProjects() throws Exception {
        // A task of the same name in another project is never the one certified.
        final Path state = context().paths().tasks().containerState("porelo-other-fix");
        Files.createDirectories(state);
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "other",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        final StringWriter uc = new StringWriter();
        final StringWriter other = new StringWriter();

        assertThat(cli(uc).execute("task", "certify", "-p", "uc", "fix")).isEqualTo(66);
        assertThat(uc.toString()).contains("fix is no task of project uc here");
        assertThat(cli(other).execute("task", "certify", "-p", "other", "fix")).isEqualTo(66);
        assertThat(other.toString())
                .as("its own project's task is picked")
                .contains("no key of its own")
                .doesNotContain("is no task of project");
    }

    private PoreloContext context() {
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir.resolve("home")), dir.resolve("bin")),
                args -> 0);
    }

    private CommandLine cli(StringWriter err) {
        final CommandLine cli = PoreloCli.commandLine(context());
        cli.setOut(new PrintWriter(new StringWriter(), true));
        cli.setErr(new PrintWriter(err, true));
        return cli;
    }
}
