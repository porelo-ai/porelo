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
        final Path helper = dir.resolve("porelo-host-person");
        assertThat(new HostPerson(context(), helper).present()).isFalse();
        Files.writeString(helper, "#!/bin/sh\n");
        helper.toFile().setExecutable(true);
        assertThat(new HostPerson(context(), helper).present()).isTrue();
    }

    @Test
    void theStartHandsTheCertificateOnItsInputAndTakesTheTokenFromItsOutput() {
        runner.answering("task fix", "forge-token-value");
        final HostPerson host = new HostPerson(context(), dir.resolve("helper"));

        assertThat(host.start("fix", "ssh-ed25519-cert-v01@openssh.com AAAA"))
                .isEqualTo("forge-token-value");
        final var call = runner.invocations().getLast();
        assertThat(call.arguments())
                .containsExactly("sudo", "-n", dir.resolve("helper").toString(), "task", "fix");
        assertThat(call.input()).isEqualTo("ssh-ed25519-cert-v01@openssh.com AAAA\n");
        assertThat(call.describe()).doesNotContain("AAAA");
    }

    @Test
    void aRefusalSaysWhyAndAnEndIsCheckedByItsAnswer() {
        runner.failing("task fix", 65, "the certificate is not signed by alice's current key\n");
        final HostPerson host = new HostPerson(context(), dir.resolve("helper"));
        assertThatThrownBy(() -> host.start("fix", "cert"))
                .isInstanceOf(HostPerson.Refused.class)
                .hasMessageContaining("not signed by alice's current key")
                .satisfies(ex -> assertThat(((HostPerson.Refused) ex).code()).isEqualTo(65));

        runner.answering("task --end fix", "{\"task\":\"fix\",\"ended\":true}\n");
        assertThat(host.end("fix")).isTrue();
        runner.answering("task --end other", "{\"task\":\"fix\",\"ended\":true}\n");
        assertThat(host.end("other")).as("an answer about another task").isFalse();
    }

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(name -> null, dir.resolve("home")), dir.resolve("bin")),
                args -> 0);
    }
}
