package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The checkouts a person names once, for signing a repository's file from them. Harm guarded: a
 * setting that names no checkout of this account would only fail at the signing, so it is refused
 * when named.
 */
class SetupCheckoutsTest {

    @TempDir private Path dir;

    private RegistryCheckout machine;

    @BeforeEach
    void aMachine() throws Exception {
        machine = new RegistryCheckout(dir);
    }

    @AfterEach
    void close() {
        machine.close();
    }

    private Path checkout(final String name) {
        final Path where = dir.resolve(name);
        machine.runner.runOrFail(
                Command.of("git", "init", "-q", "-b", "main", where.toString())
                        .withEnvironment(Map.of("GIT_CONFIG_GLOBAL", "/dev/null")));
        machine.runner.runOrFail(
                Command.of(
                                "git",
                                "-c",
                                "user.name=Person",
                                "-c",
                                "user.email=person@example.org",
                                "commit",
                                "-q",
                                "--allow-empty",
                                "-m",
                                "first")
                        .in(where)
                        .withEnvironment(Map.of("GIT_CONFIG_GLOBAL", "/dev/null")));
        return where;
    }

    private int setup(final String... arguments) {
        machine.out.getBuffer().setLength(0);
        machine.err.getBuffer().setLength(0);
        final CommandLine line = PoreloCli.commandLine(machine.context());
        line.setOut(new PrintWriter(machine.out));
        line.setErr(new PrintWriter(machine.err));
        final String[] all = new String[arguments.length + 1];
        all[0] = "setup";
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return line.execute(all);
    }

    @Test
    void theOrganizationsAndAProjectsCheckoutAreNamedOnceAndKept() throws Exception {
        final Path organization = checkout("org");
        final Path demo = checkout("demo");

        assertThat(
                        setup(
                                "--organization-checkout",
                                organization.toString(),
                                "--project-checkout",
                                "demo=" + demo,
                                "--json"))
                .as("set: %s", machine.err)
                .isZero();

        final OrgCheckouts kept = OrgCheckouts.of(machine.context());
        assertThat(kept.organization()).isEqualTo(organization);
        assertThat(kept.projects()).containsExactly(Map.entry("demo", demo));
        assertThat(machine.out.toString()).contains("\"organization\"").contains(demo.toString());

        final Path web = checkout("web");
        assertThat(setup("--project-checkout", "web=" + web)).isZero();
        assertThat(OrgCheckouts.of(machine.context()).projects())
                .as("another project's checkout is added, the first kept")
                .containsOnlyKeys("demo", "web");
        assertThat(OrgCheckouts.of(machine.context()).organization()).isEqualTo(organization);
    }

    @Test
    void aDirectoryThatIsNoCheckoutIsRefusedAndNothingIsWritten() throws Exception {
        final Path plain = Files.createDirectory(dir.resolve("plain"));

        assertThat(setup("--organization-checkout", plain.toString(), "--json")).isEqualTo(64);

        assertThat(String.valueOf(machine.answer().get("words"))).contains("no git checkout");
        assertThat(OrgCheckouts.of(machine.context()).organization()).as("nothing named").isNull();
    }

    @Test
    void aProjectNameNoProjectCanHaveIsRefused() {
        final Path demo = checkout("demo");

        assertThat(setup("--project-checkout", "../x=" + demo, "--json")).isEqualTo(64);

        assertThat(List.of(String.valueOf(machine.answer().get("words"))))
                .first()
                .asString()
                .contains("names no project");
    }
}
