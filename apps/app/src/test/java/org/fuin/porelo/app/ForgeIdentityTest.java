package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ForgeIdentity}: asking a host who it thinks this machine is decides nothing
 * about the host, and runs nothing but the question.
 */
class ForgeIdentityTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private List<Command> sshRuns() {
        return runner.invocations().stream()
                .filter(command -> "ssh".equals(command.arguments().get(0)))
                .toList();
    }

    @Test
    void askingAHostWhoWeAreNeverLearnsAHostKeyNobodyConfirmed() {
        // It asked with accept-new, into the known_hosts every fetch trusts: a check of a
        // credential, or the explanation of a refused fetch, trusted a host nobody had compared,
        // and
        // the fetch after it never asked whether the host was the right one.
        runner.answering("BatchMode=yes", "", "Hi acme/demo! You've successfully authenticated.");

        final ForgeIdentity.Answer answer =
                ForgeIdentity.ask(context(), "git@forge.example:acme/demo.git", "identity");

        assertThat(answer).isNotNull();
        assertThat(sshRuns())
                .singleElement()
                .satisfies(
                        ssh ->
                                assertThat(ssh.arguments())
                                        .as("the host-key policy a fetch uses: " + ssh.describe())
                                        .contains("StrictHostKeyChecking=yes")
                                        .noneMatch(argument -> argument.contains("accept-new")));
    }

    @Test
    void anAddressWhoseUserIsAnOptionIsNotAskedAbout() {
        // git refuses such an address before ssh sees it; asked here after the refused fetch, ssh
        // took '-oProxyCommand=...' for an option and ran the command it names.
        final ForgeIdentity.Answer answer =
                ForgeIdentity.ask(
                        context(),
                        "ssh://-oProxyCommand=touch${IFS}pwned@forge.example/acme/demo.git",
                        "identity");

        assertThat(answer).isNull();
        assertThat(sshRuns()).as("no ssh was run").isEmpty();
    }
}
