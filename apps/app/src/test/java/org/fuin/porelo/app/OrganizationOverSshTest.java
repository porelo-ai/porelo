package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Harm guarded: a person whose key sits in their own agent creates an organization over ssh and can
 * then read nothing of it, because git runs with a chosen environment holding no agent.
 *
 * <p>Ssh is a stand-in here: it notes the agent git's environment names, refuses as ssh refuses
 * when there is none, and serves an empty repository otherwise. No agent runs, and the socket named
 * is only a path. So these show what git is lent at the step that failed, not a whole answer:
 * reading a founded organization also asks its forge.
 */
class OrganizationOverSshTest {

    private static final String ADDRESS = "ssh://git@forge.example.invalid/acme/organization.git";

    @TempDir private Path dir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private Path noted;
    private Path standIn;
    private Path socket;

    @BeforeEach
    void standInForSsh() throws Exception {
        noted = dir.resolve("agents-seen");
        socket = dir.resolve("agent.sock");
        final Path bare = dir.resolve("organization.git");
        final CommandRunner real = new ProcessCommandRunner();
        real.runOrFail(Command.of("git", "init", "-q", "--bare", bare.toString()));
        standIn = dir.resolve("ssh-stand-in");
        Files.writeString(
                standIn,
                "#!/bin/sh\n"
                        + "printf '[%s]\\n' \"$SSH_AUTH_SOCK\" >> '"
                        + noted
                        + "'\n"
                        + "if [ -z \"$SSH_AUTH_SOCK\" ]; then\n"
                        + "  echo 'git@forge: Permission denied (publickey).' >&2\n"
                        + "  exit 255\n"
                        + "fi\n"
                        + "exec git upload-pack '"
                        + bare
                        + "'\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(standIn, PosixFilePermissions.fromString("rwx------"));
    }

    /** Runs everything for real, with the stand-in where git would run ssh. */
    private CommandRunner runner() {
        final CommandRunner real = new ProcessCommandRunner();
        return command ->
                real.run(
                        command.environment().containsKey("GIT_SSH_COMMAND")
                                ? command.withEnvironment(
                                        Map.of("GIT_SSH_COMMAND", standIn.toString()))
                                : command);
    }

    private PoreloContext machine(final @Nullable Path agent) {
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
                runner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0,
                name -> "SSH_AUTH_SOCK".equals(name) && agent != null ? agent.toString() : null);
    }

    private void settings() throws Exception {
        final Path file = dir.resolve("config/porelo/organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "adapter: git\nsettings:\n  repository: " + ADDRESS + "\n");
    }

    /** What {@code credentials declare ssh://HOST --kind ssh-key --agent} leaves. */
    private void declaredAgent(final PoreloContext machine) throws Exception {
        final Path file = machine.paths().vault().credentialRegistry();
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "credentials:\n  - match: ssh://forge.example.invalid\n"
                        + "    kind: ssh-key\n    source: agent\n");
    }

    private int run(final PoreloContext machine, final String... args) {
        final CommandLine line = PoreloCli.commandLine(machine);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line.execute(args);
    }

    private List<String> agentsSeen() throws Exception {
        return Files.exists(noted) ? Files.readAllLines(noted) : List.of();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_read_is_lent_the_persons_agent(final boolean declared) throws Exception {
        final PoreloContext machine = machine(socket);
        settings();
        if (declared) {
            declaredAgent(machine);
        }

        run(machine, "org", "--json");

        assertThat(agentsSeen()).isNotEmpty().containsOnly("[" + socket + "]");
    }

    @Test
    void naming_an_organization_is_lent_the_persons_agent() throws Exception {
        final PoreloContext machine = machine(socket);

        run(machine, "setup", "--organization", ADDRESS, "--json");

        assertThat(agentsSeen()).isNotEmpty().containsOnly("[" + socket + "]");
        assertThat(err.toString())
                .as("reading it can take long, and says so")
                .contains("porelo: reading the organization's repository");
    }

    @Test
    void a_start_reading_the_organization_is_lent_the_persons_agent() throws Exception {
        final PoreloContext machine = machine(socket);
        settings();

        try {
            ProjectTaking.take(machine, "demo");
        } catch (ProjectTaking.Refused ex) {
            // The stand-in's repository holds nothing: what counts is what its fetch was lent.
        }

        assertThat(agentsSeen()).isNotEmpty().containsOnly("[" + socket + "]");
    }

    @Test
    void a_project_file_read_is_lent_the_persons_agent() throws Exception {
        final PoreloContext machine = machine(socket);

        new ProjectFile(
                        machine.runner(),
                        OrganizationAccess.lent(machine),
                        CommitSigning.agent(""),
                        dir.resolve("project-files"))
                .read(ADDRESS, List.of());

        assertThat(agentsSeen()).isNotEmpty().containsOnly("[" + socket + "]");
    }

    /** The stand-in is honest: with no agent it refuses, as the person saw. */
    @Test
    void with_no_agent_at_all_a_read_cannot_say() throws Exception {
        final PoreloContext machine = machine(null);
        settings();

        run(machine, "org", "--json");

        assertThat(agentsSeen()).isNotEmpty().containsOnly("[]");
        assertThat(out.toString()).contains("cannot-say");
    }
}
