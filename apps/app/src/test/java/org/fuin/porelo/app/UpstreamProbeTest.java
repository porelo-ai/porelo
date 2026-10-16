package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An upstream read as the gate's clone reads it, before a start makes anything.
 * <p>
 * <strong>What is measured is that the cause is named with its fix.</strong> A key the forge refused surfaced from the
 * clone as "no git gate for this task" with git's raw words, after the task was half made (found on 2026-10-13).
 */
class UpstreamProbeTest {

    private static final String UPSTREAM = "ssh://git@forge.example.org:7999/project/backend.git";

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(final Path dir, final String securityClass) throws Exception {
        final Path file = dir.resolve("project/project.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                project:
                  name: "uc"
                  security_class: "%s"
                image:
                  base_image: "ubuntu:24.04"
                repositories:
                  backend:
                    upstream: "%s"
                  local:
                    upstream: "/srv/git/local.git"
                """.formatted(securityClass, UPSTREAM));
        return ProjectReader.read(file);
    }

    private void forgeSays(final String said) {
        runner.failing("ls-remote", 128, said);
    }

    @Test
    void aKeyTheForgeRefusedIsNamedWithWhatWasOfferedAndWhatStoresOne(@TempDir final Path dir) {

        forgeSays("git@forge.example.org: Permission denied (publickey).\n"
                + "fatal: Could not read from remote repository.");

        final UpstreamProbe.Answer answer = UpstreamProbe.probe(context(dir),
                GateSupport.credentials(context(dir), "uc", "backend"), UPSTREAM);

        assertThat(answer).isNotNull();
        assertThat(answer.cause()).isEqualTo(UpstreamProbe.Cause.KEY_REFUSED);
        assertThat(answer.detail()).contains("forge.example.org:7999 refused this machine")
                .contains("default key files").contains("Through the daemon only a key in the vault");
        assertThat(answer.fix()).startsWith("porelo vault put git.ssh.forge.example.org");
    }

    @Test
    void eachCauseIsToldFromWhatGitAndSshSaid(@TempDir final Path dir) {

        final PoreloContext context = context(dir);

        assertThat(UpstreamProbe.answer(context, UPSTREAM, "No ED25519 host key is known for [forge.example.org]:7999"
                + " and you have requested strict checking.\nHost key verification failed.").fix())
                .isEqualTo("porelo credentials trust-host forge.example.org:7999");
        assertThat(UpstreamProbe.answer(context, UPSTREAM, "ERROR: Repository not found.").cause())
                .isEqualTo(UpstreamProbe.Cause.NOT_FOUND);
        assertThat(UpstreamProbe.answer(context, UPSTREAM,
                "ssh: Could not resolve hostname forge.example.org: Name or service not known").cause())
                .isEqualTo(UpstreamProbe.Cause.UNREACHABLE);
        assertThat(UpstreamProbe.answer(context, UPSTREAM,
                "ssh: connect to host 192.0.2.10 port 7999: Connection timed out").cause())
                .isEqualTo(UpstreamProbe.Cause.UNREACHABLE);
        assertThat(UpstreamProbe.answer(context, "https://forge.example.org/project/backend.git",
                "fatal: could not read Username for 'https://forge.example.org': terminal prompts disabled").cause())
                .isEqualTo(UpstreamProbe.Cause.KEY_REFUSED);
        final UpstreamProbe.Answer other = UpstreamProbe.answer(context, UPSTREAM, "fatal: something else\n");
        assertThat(other.cause()).isEqualTo(UpstreamProbe.Cause.UNREADABLE);
        assertThat(other.detail()).endsWith("fatal: something else");
    }

    @Test
    void neverAsksOnATerminalAndNeverWaitsWithoutEnd(@TempDir final Path dir) {

        // A start has no terminal for ssh to ask a passphrase on, and an unreachable host must cost seconds.
        UpstreamProbe.probe(context(dir), GateSupport.credentials(context(dir), "uc", "backend"), UPSTREAM);

        final var asked = runner.only("ls-remote");
        assertThat(asked.arguments()).containsSubsequence("ls-remote", "--heads", "--end-of-options", UPSTREAM);
        assertThat(asked.environment().get("GIT_SSH_COMMAND"))
                .startsWith("ssh -o BatchMode=yes -o ConnectTimeout=10 ").contains("StrictHostKeyChecking=yes");
        assertThat(asked.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    @Test
    void withoutAMirrorTheStartIsRefusedAndWithOneItGoesOnFromIt(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        forgeSays("git@forge.example.org: Permission denied (publickey).");

        assertThat(UpstreamProbe.forStart(context, project, project.repository("backend"), null).refuses())
                .as("the clone needs the upstream").isTrue();

        Files.createDirectories(GateSupport.mirror(context, project, project.repository("backend")).resolve("objects"));
        assertThat(UpstreamProbe.forStart(context, project, project.repository("backend"), null).refuses())
                .as("a mirror is there to start from").isFalse();
    }

    @Test
    void asksNothingOfALocalPath(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");

        assertThat(UpstreamProbe.forStart(context, project, project.repository("local"), null)).isNull();
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void anUpstreamThatIsReadIsNoQuestion(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        runner.answering("ls-remote", "0123456789abcdef0123456789abcdef01234567\trefs/heads/main\n");

        assertThat(UpstreamProbe.forStart(context, project, project.repository("backend"), null)).isNull();
    }

    @Test
    void aReadCountsForAWhile(@TempDir final Path dir) throws Exception {

        // A start dialog asks every few seconds while a person chooses; each ask reached the network.
        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        runner.answering("ls-remote", "0123456789abcdef0123456789abcdef01234567\trefs/heads/main\n");

        UpstreamProbe.forStart(context, project, project.repository("backend"), null);
        UpstreamProbe.forStart(context, project, project.repository("backend"), null);

        assertThat(runner.invocations()).as("read once").hasSize(1);
    }

    @Test
    void aRefusalIsAskedAgainSoAFixIsSeenAtOnce(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        forgeSays("git@forge.example.org: Permission denied (publickey).");

        UpstreamProbe.forStart(context, project, project.repository("backend"), null);
        UpstreamProbe.forStart(context, project, project.repository("backend"), null);

        assertThat(runner.invocations()).hasSize(2);
    }
}
