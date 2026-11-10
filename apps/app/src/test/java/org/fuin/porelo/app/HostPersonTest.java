package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link HostPerson}: a task's start and end told to the host, by its contract. */
class HostPersonTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    @Test
    void aHostIsOneWithItsPersonsHelper() throws Exception {
        final Path helper = dir.resolve("porelo-host");
        assertThat(new HostPerson(context(), helper, dir.resolve("absent-host")).present())
                .isFalse();
        Files.writeString(helper, "#!/bin/sh\n");
        helper.toFile().setExecutable(true);
        assertThat(new HostPerson(context(), helper, dir.resolve("absent-host")).present())
                .isTrue();
    }

    @Test
    void aHelperOnlyRootMayRunStillMakesAHost() throws Exception {
        final Path helper = dir.resolve("porelo-host");
        Files.writeString(helper, "#!/bin/sh\n");
        helper.toFile().setExecutable(false);
        assertThat(new HostPerson(context(), helper, dir.resolve("absent-host")).present())
                .isTrue();
    }

    @Test
    void theStartHandsTheCertificateOnItsInputAndTakesTheTokenFromItsOutput() {
        runner.answering("task start fix", "forge-token-value");
        final HostPerson host =
                new HostPerson(context(), dir.resolve("helper"), dir.resolve("absent-host"));

        assertThat(host.start("fix", "ssh-ed25519-cert-v01@openssh.com AAAA"))
                .isEqualTo("forge-token-value");
        final var call = runner.invocations().getLast();
        assertThat(call.arguments())
                .containsExactly(
                        "sudo", "-n", dir.resolve("helper").toString(), "task", "start", "fix");
        assertThat(call.input()).isEqualTo("ssh-ed25519-cert-v01@openssh.com AAAA\n");
        assertThat(call.describe()).doesNotContain("AAAA");
    }

    @Test
    void oneLineEndAfterTheTokenIsNoPartOfIt() {
        // A token kept with its line end fails at the forge, so the task's every push would.
        runner.answering("task start fix", "forge-token-value\n");
        final HostPerson host =
                new HostPerson(context(), dir.resolve("helper"), dir.resolve("absent-host"));

        assertThat(host.start("fix", "cert")).isEqualTo("forge-token-value");
        runner.answering("task start fix", "");
        assertThat(host.start("fix", "cert")).as("a host without a forge").isEmpty();
    }

    @Test
    void aRefusalSaysWhyAndAnEndIsCheckedByItsAnswer() {
        runner.failing(
                "task start fix", 65, "the certificate is not signed by alice's current key\n");
        final HostPerson host =
                new HostPerson(context(), dir.resolve("helper"), dir.resolve("absent-host"));
        assertThatThrownBy(() -> host.start("fix", "cert"))
                .isInstanceOf(HostPerson.Refused.class)
                .hasMessageContaining("not signed by alice's current key")
                .satisfies(ex -> assertThat(((HostPerson.Refused) ex).code()).isEqualTo(65));

        runner.answering("task end fix", "{\"task\":\"fix\",\"ended\":true}\n");
        assertThat(host.end("fix")).isTrue();
        runner.answering("task end other", "{\"task\":\"fix\",\"ended\":true}\n");
        assertThat(host.end("other")).as("an answer about another task").isFalse();
    }

    @org.junit.jupiter.api.BeforeEach
    void installedHelper() throws Exception {
        Files.writeString(dir.resolve("helper"), "fixture");
    }

    @Test
    void aMissingHostToolRefusesWithoutRunningAnOldScript() {
        final HostPerson host =
                new HostPerson(context(), dir.resolve("absent"), dir.resolve("absent-host"));
        assertThatThrownBy(() -> host.start("fix", "cert"))
                .isInstanceOfSatisfying(
                        HostPerson.Refused.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(69);
                            assertThat(ex.getMessage())
                                    .contains("/usr/sbin/porelo-host", "install");
                        });
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aConfiguredHostWithoutTheToolStillAttemptsNoOldScript() throws Exception {
        final Path configuration = Files.createDirectory(dir.resolve("host-configuration"));
        final HostPerson host = new HostPerson(context(), dir.resolve("missing"), configuration);
        assertThat(host.present()).isTrue();
        assertThatThrownBy(() -> host.end("fix"))
                .isInstanceOf(HostPerson.Refused.class)
                .hasMessageContaining("/usr/sbin/porelo-host");
        assertThatThrownBy(() -> host.show("SHA256:" + "A".repeat(43)))
                .isInstanceOf(HostPerson.Refused.class)
                .hasMessageContaining("/usr/sbin/porelo-host");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aMachineWithoutAHostNeverAsksSudoForATaskRecord() {
        final HostPerson host =
                new HostPerson(context(), dir.resolve("absent"), dir.resolve("absent-host"));
        assertThat(host.show("SHA256:" + "A".repeat(43))).isNull();
        assertThat(runner.invocations()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {64, 65, 69, 73, 77, 78})
    void theHostWrappersRefusalKeepsTheHelpersCode(int code) {
        runner.failing("task start fix", code, "safe refusal");
        final HostPerson host =
                new HostPerson(context(), dir.resolve("helper"), dir.resolve("absent-host"));
        assertThatThrownBy(() -> host.start("fix", "certificate"))
                .isInstanceOfSatisfying(
                        HostPerson.Refused.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(code));
    }

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(name -> null, dir.resolve("home")), dir.resolve("bin")),
                args -> 0);
    }
}
