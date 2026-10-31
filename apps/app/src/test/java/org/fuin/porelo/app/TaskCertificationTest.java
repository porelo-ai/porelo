package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskCertification}, with certificates {@code ssh-keygen -s} made. */
class TaskCertificationTest {

    @TempDir Path dir;

    private final Clock clock = Clock.systemUTC();

    @Test
    void aTaskKeyCertifiedByAnAllowedPersonIsAcceptedWithItsSignersLines() throws Exception {
        final String person = keygen("person");

        final TaskCertification.Issued issued =
                TaskCertification.certify(
                        "demo",
                        "fix-1a2b",
                        "alice",
                        certifier("person", null),
                        List.of(person),
                        clock);

        assertThat(issued.certificate().principals()).containsExactly("task:demo:fix-1a2b");
        assertThat(issued.key().authorizedKeysLine()).startsWith(issued.certificate().key() + " ");
        assertThat(issued.certificate().keyId()).isEqualTo("porelo task fix-1a2b by alice");
        assertThat(issued.principal()).isEqualTo("task:demo:fix-1a2b");
        assertThat(issued.signers())
                .isEqualTo("task:demo:* cert-authority,namespaces=\"git\" " + person + "\n");
        assertThat(issued.certificate().validBefore())
                .isBefore(Instant.now().plus(TaskCertification.VALIDITY).plusSeconds(120));
    }

    @Test
    void aTaskWithNoProjectIsCertifiedByItsStartingPersonAlone() throws Exception {
        final String person = keygen("person");

        final TaskCertification.Issued issued =
                TaskCertification.certify(
                        "default",
                        "shell-9f00",
                        "alice",
                        certifier("person", null),
                        List.of(),
                        clock);

        assertThat(issued.principal()).isEqualTo("task:default:shell-9f00");
        assertThat(issued.signers())
                .isEqualTo("task:default:* cert-authority,namespaces=\"git\" " + person + "\n");
    }

    @Test
    void aProjectNamingNoCertifierHasNoTaskCertifiedAndNothingAsked() throws Exception {
        keygen("person");
        final java.util.concurrent.atomic.AtomicBoolean asked =
                new java.util.concurrent.atomic.AtomicBoolean();
        final TaskCertification.Certifier certifier = certifier("person", null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        (key, principal, keyId, before) -> {
                                            asked.set(true);
                                            return certifier.certify(key, principal, keyId, before);
                                        },
                                        List.of(),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("no key of project demo's access file may certify it");
        assertThat(asked).isFalse();
    }

    @Test
    void aRenewalCertifiesTheSameKeyAgain() throws Exception {
        final String person = keygen("person");
        final TaskCertification.Issued first =
                TaskCertification.certify(
                        "demo", "fix", "alice", certifier("person", null), List.of(person), clock);

        final TaskCertification.Issued renewed =
                TaskCertification.certify(
                        "demo",
                        "fix",
                        "alice",
                        first.key(),
                        certifier("person", null),
                        List.of(person),
                        clock);

        assertThat(renewed.certificate().key()).isEqualTo(first.certificate().key());
        assertThat(renewed.certificate().line()).isNotEqualTo(first.certificate().line());
    }

    @Test
    void whatTheCertifierAnswersIsCheckedBeforeAnythingIsKept() throws Exception {
        final String person = keygen("person");
        keygen("stranger");

        assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        certifier("stranger", null),
                                        List.of(person),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("not a certifier");
        assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        certifier("person", "task:demo:other"),
                                        List.of(person),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("task:demo:fix");
        assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        (key, principal, id, before) -> certifyOther(principal),
                                        List.of(person),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("another key");
        assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        (key, principal, id, before) ->
                                                sign("person", key, principal, "+30d", id),
                                        List.of(person),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("longer");
        assertThatThrownBy(
                        () ->
                                TaskCertification.certify(
                                        "demo",
                                        "fix",
                                        "alice",
                                        (key, principal, id, before) -> "not a certificate",
                                        List.of(person),
                                        clock))
                .isInstanceOf(TaskCertification.Refused.class);
    }

    private TaskCertification.Certifier certifier(String ca, String principalOverride) {
        return (key, principal, id, before) ->
                sign(ca, key, principalOverride == null ? principal : principalOverride, "+7d", id);
    }

    private String certifyOther(String principal) {
        try {
            keygen("other-task");
            return sign(
                    "person",
                    Files.readString(dir.resolve("other-task.pub")).strip(),
                    principal,
                    "+1d",
                    "id");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sign(String ca, String key, String principal, String validity, String id) {
        try {
            final Path pub = dir.resolve("certified.pub");
            Files.writeString(pub, key + "\n");
            final List<String> command =
                    new ArrayList<>(
                            List.of(
                                    "ssh-keygen",
                                    "-q",
                                    "-s",
                                    dir.resolve(ca).toString(),
                                    "-I",
                                    id,
                                    "-n",
                                    principal,
                                    "-V",
                                    validity,
                                    pub.toString()));
            run(command.toArray(String[]::new));
            final Path cert = dir.resolve("certified-cert.pub");
            final String line = Files.readString(cert).strip();
            Files.delete(cert);
            return line;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String keygen(String name) throws Exception {
        final Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name, "-f", file.toString());
        }
        final String[] fields = Files.readString(dir.resolve(name + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }

    private static void run(String... command) throws Exception {
        assertThat(
                        new ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .as(String.join(" ", command))
                .isZero();
    }

    @SuppressWarnings("unused")
    private static final Duration UNUSED = Duration.ZERO;
}
