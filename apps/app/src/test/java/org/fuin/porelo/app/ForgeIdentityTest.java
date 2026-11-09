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

    // A credential probe must not trust another host or forward the person's agent to it.
    @Test
    void aProbeIsolatesSshTrustEvenWithoutALentKey() throws Exception {
        ForgeIdentity.ask(context(), "git@forge.example:team/repo.git", "probe");

        final Command ssh = sshRuns().getFirst();
        SshPolicyFixture.assertIsolated(
                SshPolicyFixture.effective(
                        dir, ssh.arguments().subList(1, ssh.arguments().size())));
        assertThat(ssh.inheritsEnvironment()).isFalse();
        assertThat(ssh.environment()).containsEntry("SSH_ASKPASS_REQUIRE", "never");
    }

    @Test
    void aProbeUsesTheSelectedAgentIdentityRatherThanAnotherKey() throws Exception {
        // A successful check of a different identity would authorize keeping the wrong key.
        final var paths =
                XdgPaths.of(
                        name ->
                                "XDG_RUNTIME_DIR".equals(name)
                                        ? dir.resolve("run").toString()
                                        : null,
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(paths, dir.resolve("bin")), args -> 0);
        final String key =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ForgeIdentity.ask(
                context,
                "git@forge.example:team/repo.git",
                "probe",
                new org.fuin.porelo.core.credential.Credential(
                        key,
                        org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                        "ssh://forge.example/team/repo.git",
                        null,
                        "git",
                        org.fuin.porelo.core.credential.Credential.Source.AGENT));
        assertThat(sshRuns())
                .as("the direct SSH probe uses the same isolated identity as git")
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.arguments())
                                        .as("no configured file or other agent identity may win")
                                        .contains("-i", "IdentitiesOnly=yes", "-F", "/dev/null"));
    }

    @Test
    void askingAHostWhoWeAreNeverLearnsAHostKeyNobodyConfirmed() {
        // It asked with accept-new, into the known_hosts every fetch trusts: a check of a
        // credential, or the explanation of a refused fetch, trusted a host nobody had compared,
        // and the fetch after it never asked whether the host was the right one.
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
