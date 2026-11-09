package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;

/** OpenSSH interprets each caller's options against settings that would widen trust. */
final class SshPolicyFixture {
    private SshPolicyFixture() {}

    static Path config(Path dir) throws Exception {
        final Path config = dir.resolve("ssh_config");
        Files.writeString(
                config,
                """
                Host *
                  GlobalKnownHostsFile /fixture/global_hosts
                  KnownHostsCommand /bin/true
                  VerifyHostKeyDNS yes
                  ForwardAgent yes
                  ControlMaster auto
                  ControlPath /fixture/master
                  ProxyCommand /bin/true
                """);
        final var baseline =
                new ProcessCommandRunner()
                        .run(Command.of("ssh", "-G", "-F", config.toString(), "example.org"));
        assertThat(baseline.exitCode()).as(baseline.standardError()).isZero();
        assertThat(baseline.standardOutput())
                .contains(
                        "globalknownhostsfile /fixture/global_hosts",
                        "knownhostscommand /bin/true",
                        "verifyhostkeydns true",
                        "forwardagent yes",
                        "controlmaster auto",
                        "controlpath /fixture/master",
                        "proxycommand /bin/true");
        return config;
    }

    static String effective(Path dir, List<String> arguments) throws Exception {
        final var asking = new ArrayList<>(List.of("ssh", "-G", "-F", config(dir).toString()));
        asking.addAll(arguments);
        final var effective = new ProcessCommandRunner().run(Command.of(asking));
        assertThat(effective.exitCode()).as(effective.standardError()).isZero();
        return effective.standardOutput();
    }

    static String effectiveShell(Path dir, String command) throws Exception {
        final Path config = config(dir);
        final var effective =
                new ProcessCommandRunner()
                        .run(
                                Command.of(
                                        "sh",
                                        "-c",
                                        "exec ssh -G -F '"
                                                + config
                                                + "'"
                                                + command.substring(3)
                                                + " git@example.org"));
        assertThat(effective.exitCode()).as(effective.standardError()).isZero();
        return effective.standardOutput();
    }

    static void assertIsolated(String effective) {
        assertThat(effective)
                .contains(
                        "batchmode yes",
                        "stricthostkeychecking true",
                        "globalknownhostsfile /dev/null",
                        "updatehostkeys false",
                        "verifyhostkeydns false",
                        "forwardagent no",
                        "clearallforwardings yes",
                        "controlmaster false")
                .doesNotContain("knownhostscommand ", "controlpath ", "proxycommand ");
    }
}
