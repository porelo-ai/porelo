package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SshCertificate}, against certificates {@code ssh-keygen -s} made. */
class SshCertificateTest {

    @TempDir Path dir;

    @Test
    void reads_what_ssh_keygen_certified_and_checks_its_signer() throws Exception {
        final String person = key("person");
        final String task = key("task");

        final SshCertificate certificate =
                SshCertificate.read(
                        certify("person", "task", "porelo task demo-1a2b by alice", "+7d"));

        assertThat(certificate.keyId()).isEqualTo("porelo task demo-1a2b by alice");
        assertThat(certificate.principals()).containsExactly("task:demo:demo-1a2b");
        assertThat(certificate.key()).isEqualTo(task);
        assertThat(certificate.signer()).isEqualTo(person);
        assertThat(certificate.fingerprint()).isEqualTo(PersonCard.fingerprint(task));
        assertThat(certificate.validBefore())
                .isBetween(
                        Instant.now().plusSeconds(7 * 86_400 - 300),
                        Instant.now().plusSeconds(7 * 86_400 + 300));
        assertThat(certificate.validAt(Instant.now())).isTrue();
        assertThat(certificate.validAt(certificate.validBefore()))
                .as("valid-before is the first second it is not")
                .isFalse();
        assertThat(certificate.validAt(certificate.validAfter().minusSeconds(1))).isFalse();
    }

    @Test
    void a_line_of_another_signer_or_changed_after_signing_is_refused() throws Exception {
        key("person");
        key("other");
        key("task");
        final String line = certify("other", "task", "porelo task demo-1a2b by alice", "+7d");

        final SshCertificate certificate = SshCertificate.read(line);
        assertThat(certificate.signer())
                .as("the signer is named, for the caller to judge")
                .isEqualTo(keyOnly(read("other.pub")))
                .isNotEqualTo(keyOnly(read("person.pub")));

        // One byte of the principal changed after signing: the CA's signature no longer holds.
        final byte[] blob = blob(line);
        final byte[] principal = "task:demo:demo-1a2b".getBytes(StandardCharsets.US_ASCII);
        final int at = indexOf(blob, principal);
        assertThat(at).isPositive();
        blob[at + principal.length - 1] ^= 1;
        assertThatThrownBy(() -> SshCertificate.read(lineOf(blob)))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void refuses_a_host_certificate_critical_options_trailing_bytes_and_other_types()
            throws Exception {
        key("person");
        key("task");
        assertThatThrownBy(() -> SshCertificate.read(certify("person", "task", "id", "+1d", "-h")))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("user certificate");
        assertThatThrownBy(
                        () ->
                                SshCertificate.read(
                                        certify(
                                                "person",
                                                "task",
                                                "id",
                                                "+1d",
                                                "-O",
                                                "force-command=/bin/true")))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("critical option");

        final byte[] blob = blob(certify("person", "task", "id", "+1d"));
        final byte[] longer = java.util.Arrays.copyOf(blob, blob.length + 1);
        assertThatThrownBy(() -> SshCertificate.read(lineOf(longer)))
                .isInstanceOf(CardException.class);
        assertThatThrownBy(() -> SshCertificate.read(read("task.pub")))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("ssh-ed25519-cert-v01@openssh.com");
        assertThatThrownBy(() -> SshCertificate.read("ssh-ed25519-cert-v01@openssh.com AAAA!"))
                .isInstanceOf(CardException.class);
    }

    @Test
    void refuses_a_certificate_whose_signer_is_not_ed25519() throws Exception {
        run(
                "ssh-keygen",
                "-q",
                "-t",
                "ecdsa",
                "-N",
                "",
                "-C",
                "ca",
                "-f",
                dir.resolve("ec").toString());
        key("task");
        assertThatThrownBy(() -> SshCertificate.read(certify("ec", "task", "id", "+1d")))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("ssh-ed25519");
    }

    @Test
    void a_signature_made_with_the_certificate_verifies_against_a_cert_authority_line()
            throws Exception {
        final String person = key("person");
        key("task");
        final String line = certify("person", "task", "porelo task demo-1a2b by alice", "+7d");
        final SigningKey task =
                new SigningKey(
                        Base64.getDecoder()
                                .decode(
                                        OpenSshPrivateKey.seedBase64(
                                                Files.readString(dir.resolve("task")))),
                        "task");
        final byte[] payload = "tree 0\n\nchange\n".getBytes(StandardCharsets.UTF_8);

        final String signature =
                SshSignature.sign(task, SshCertificate.read(line).blob(), payload, "git");

        Files.writeString(dir.resolve("payload"), new String(payload, StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("payload.sig"), signature);
        Files.writeString(
                dir.resolve("allowed"),
                "task:demo:* cert-authority,namespaces=\"git\" " + person + "\n");
        assertThat(verify("task:demo:demo-1a2b"))
                .as("ssh-keygen accepts it for the certified principal")
                .isZero();
        assertThat(verify("task:demo:other")).as("and for no other").isNotZero();
    }

    private int verify(final String principal) throws Exception {
        final Process process =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-n",
                                "git",
                                "-I",
                                principal,
                                "-f",
                                dir.resolve("allowed").toString(),
                                "-s",
                                dir.resolve("payload.sig").toString())
                        .redirectInput(dir.resolve("payload").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        return process.waitFor();
    }

    private String key(final String name) throws Exception {
        final Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name, "-f", file.toString());
        }
        return keyOnly(read(name + ".pub"));
    }

    private String certify(
            final String ca,
            final String key,
            final String id,
            final String validity,
            final String... more)
            throws Exception {
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
                                "task:demo:demo-1a2b",
                                "-V",
                                validity));
        command.addAll(List.of(more));
        command.add(dir.resolve(key + ".pub").toString());
        run(command.toArray(String[]::new));
        final String line = read(key + "-cert.pub");
        Files.delete(dir.resolve(key + "-cert.pub"));
        return line;
    }

    private String read(final String name) throws IOException {
        return Files.readString(dir.resolve(name)).strip();
    }

    private static String keyOnly(final String line) {
        final String[] fields = line.split(" ");
        return fields[0] + " " + fields[1];
    }

    private static byte[] blob(final String line) {
        return Base64.getDecoder().decode(line.split(" ")[1]);
    }

    private static String lineOf(final byte[] blob) {
        return "ssh-ed25519-cert-v01@openssh.com " + Base64.getEncoder().encodeToString(blob);
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void run(final String... command) throws Exception {
        final Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
    }
}
