package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a host offers, and the one key somebody confirms.
 * <p>
 * <strong>The rule being measured is that nothing is recorded without a person.</strong> Accepting
 * a host key on first use is easier, is what everybody does, and is exactly what an interception
 * looks like - so what is checked hardest here is what this refuses to write.
 */
class HostKeysTest {

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

    /** What ssh-keyscan prints: host, type, key. The fingerprint is ours to work out. */
    private void hostOffers(final String... keys) {
        runner.answering("ssh-keyscan", String.join("\n", keys) + "\n");
    }

    private static final String ED25519 =
            "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    /** A real blob from ssh-keygen: a made-up one is not base64 and is silently skipped, which
     * is what my first fixture here did - it measured the skipping rather than the choosing. */
    private static final String RSA_LIKE = "github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCWjk"
            + "tk/s+b+pVQf7laXiA+mz6zzNL1KiV4KewE+vOH8H1+kl93FCnan7gc1nrcdXUjqZy24yZ36ukQ8D4FCDEK"
            + "YptV+jtBFUBwhk03/QKSroai1vXo1OAOXyChjs9B1uSNeN4cE+HrkgJJSjQ/yoSoaP4jWvOgjobB6vrvNS"
            + "Xa5D+lNqdMJYPlW03WKxOZa/YaUjk2k5hspZcPc48RG0a/UBHzQnRvCYbfAnwR8/p98tEXOUgmj+6P5tVj"
            + "7bfvnjLMMndREn0zm/KbEDdx6x/G1bd70cJ0IPm2kxhgSeCky4tIZKHKL7710HwZTSQbGZnJa6650pImCX"
            + "q5vpzaBhtf";

    @Test
    void saysWhatAHostOffersWithTheFingerprintSshItselfWouldPrint(@TempDir final Path dir) {

        hostOffers(ED25519);

        final var offered = HostKeys.offeredBy(context(dir), "github.com");

        assertThat(offered).singleElement().satisfies(key -> {
            assertThat(key.type()).isEqualTo("ssh-ed25519");
            // The form a person is told out of band, and the only form worth comparing.
            assertThat(key.fingerprint()).startsWith("SHA256:").hasSizeGreaterThan(20);
        });
    }

    @Test
    void recordsOnlyTheKeyThePersonConfirmed(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        hostOffers(ED25519, RSA_LIKE);
        final var offered = HostKeys.offeredBy(context, "github.com");
        final String chosen = offered.get(0).fingerprint();

        final var recorded = HostKeys.trust(context, "github.com", chosen);

        assertThat(recorded).isNotNull();
        assertThat(recorded.fingerprint()).isEqualTo(chosen);
        // One line, not both: a person confirmed one key and the other is not theirs to vouch for.
        final String written = Files.readString(
                FollowCredential.knownHostsFile(context), StandardCharsets.UTF_8);
        assertThat(written).contains(offered.get(0).line())
                .doesNotContain(offered.get(1).line());
        assertThat(HostKeys.known(context, "github.com")).isTrue();
    }

    @Test
    void refusesAFingerprintTheHostDoesNotOfferNow(@TempDir final Path dir) throws IOException {

        // The gap this closes: a key that arrived between being shown and being confirmed. The
        // person is vouching for what they saw, and if that is no longer what the host says,
        // writing it down would record something nobody looked at.
        final PoreloContext context = context(dir);
        hostOffers(ED25519);

        assertThat(HostKeys.trust(context, "github.com", "SHA256:somethingelse")).isNull();
        assertThat(FollowCredential.knownHostsFile(context)).doesNotExist();
        assertThat(HostKeys.known(context, "github.com")).isFalse();
    }

    @Test
    void tellsAnUnknownHostFromOneWhoseKeyChanged() {

        // Two different things: one is "we have never met", the other is "this is not who I met".
        // Only the first is a question to put to somebody; the second is a warning.
        assertThat(HostKeys.refusedTheHost("Host key verification failed.")).isTrue();
        assertThat(HostKeys.changed("Host key verification failed.")).isFalse();

        final String changed = "@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@";
        assertThat(HostKeys.refusedTheHost(changed)).isTrue();
        assertThat(HostKeys.changed(changed)).isTrue();

        // And a failure that is about something else is not claimed as a host-key problem.
        assertThat(HostKeys.refusedTheHost("ERROR: Repository not found.")).isFalse();
    }

    @Test
    void anAskpassFailureIsAHostKeyProblemToo() {

        // What it actually looks like in a daemon: ssh wants to ask, finds no ssh-askpass, and
        // says so before it says anything about host keys. Reading only the last line would have
        // classified this as a missing credential - which is what it did, and what sent somebody
        // to store a key they had already stored.
        assertThat(HostKeys.refusedTheHost(
                "ssh_askpass: exec(/usr/bin/ssh-askpass): No such file or directory\n"
                        + "Host key verification failed.")).isTrue();
    }

    @Test
    void aForgeOnItsOwnPortIsAskedThereAndRecordedUnderTheNameSshLooksUp(@TempDir final Path dir) throws IOException {

        // ssh-keyscan asked port 22 and the bare name was written, so a fetch from port 7999 never found it and the
        // start kept saying it had never met the forge (found on 2026-10-13).
        final PoreloContext context = context(dir);
        runner.answering("ssh-keyscan", "[forge.example.org]:7999" + ED25519.substring("github.com".length()) + "\n");
        final String chosen = HostKeys.offeredBy(context, "forge.example.org:7999").get(0).fingerprint();

        HostKeys.trust(context, "forge.example.org:7999", chosen);

        assertThat(runner.invocations()).allSatisfy(scan -> assertThat(scan.arguments())
                .containsSubsequence("-p", "7999", "forge.example.org").doesNotContain("forge.example.org:7999"));
        assertThat(Files.readString(FollowCredential.knownHostsFile(context), StandardCharsets.UTF_8))
                .startsWith("[forge.example.org]:7999 ssh-ed25519 ");
        assertThat(HostKeys.known(context, "forge.example.org:7999")).isTrue();
        assertThat(HostKeys.known(context, "forge.example.org")).as("a key on 7999 says nothing about 22").isFalse();
    }

    @Test
    void aKeyForPort22SaysNothingAboutAnotherPort(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        hostOffers(ED25519);

        HostKeys.trust(context, "github.com:22", HostKeys.offeredBy(context, "github.com").get(0).fingerprint());

        assertThat(runner.invocations()).allSatisfy(scan -> assertThat(scan.arguments()).doesNotContain("-p"));
        assertThat(HostKeys.known(context, "github.com")).as("22 written out is the same as none").isTrue();
        assertThat(HostKeys.known(context, "github.com:2222")).isFalse();
    }

    @Test
    void readsAHostAsAPersonTypesItAndAsEveryAddressNamesIt() {

        assertThat(HostKeys.Address.of("forge.example.org:7999").entry()).isEqualTo("[forge.example.org]:7999");
        assertThat(HostKeys.Address.of("forge.example.org:22").name()).isEqualTo("forge.example.org");
        assertThat(HostKeys.Address.of("[2001:db8::1]:7999").name()).isEqualTo("[2001:db8::1]:7999");
        assertThat(HostKeys.Address.of("2001:db8::1")).as("never split at an IPv6 address's colons")
                .isEqualTo(new HostKeys.Address("2001:db8::1", 22));

        assertThat(HostKeys.nameOf("ssh://git@forge.example.org:7999/project/backend.git"))
                .isEqualTo("forge.example.org:7999");
        assertThat(HostKeys.nameOf("ssh://git@forge.example.org:22/project/backend.git")).isEqualTo("forge.example.org");
        assertThat(HostKeys.nameOf("ssh://forge.example.org/project/backend.git")).isEqualTo("forge.example.org");
        assertThat(HostKeys.nameOf("git@forge.example.org:project/backend.git")).isEqualTo("forge.example.org");
        assertThat(HostKeys.nameOf("ssh://git@[2001:db8::1]:7999/project/backend.git"))
                .isEqualTo("[2001:db8::1]:7999");
        assertThat(HostKeys.Address.ofUpstream("https://forge.example.org/project/backend.git")).isNull();
    }

    @Test
    void refusesWhatIsNoHostBeforeAnythingIsAsked() {

        // Handed to ssh-keyscan as an argument: a leading dash would be read as one of its options.
        assertThatThrownBy(() -> HostKeys.Address.of("-oProxyCommand=x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostKeys.Address.of("forge.example.org:ssh"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host:port");
        assertThatThrownBy(() -> HostKeys.Address.of("forge.example.org:70000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostKeys.Address.of(":7999")).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.invocations()).isEmpty();
    }
}
