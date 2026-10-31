package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link SshSignature}. */
class SshSignatureTest {

    private static final String NAMESPACE = "porelo-message";

    private final SigningKey key = SigningKey.generate("test");

    private final byte[] message = "{\"messageId\":\"one\"}".getBytes(StandardCharsets.UTF_8);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void erasesTheAgentSignatureEvenWhenEncodingFails(boolean fail) {
        final byte[] signature = key.sign(message, 0);
        final AgentKey agent =
                new AgentKey() {
                    public String comment() {
                        return "fixture";
                    }

                    public byte[] keyBlob() {
                        return key.keyBlob();
                    }

                    public String authorizedKeysLine() {
                        return key.authorizedKeysLine();
                    }

                    public byte[] sign(byte[] data, int flags) {
                        return signature;
                    }
                };
        try {
            if (fail) HostVaultShare.derive(agent, "SHA256:" + "A".repeat(43), "alice");
            else SshSignature.signBlob(agent, message, NAMESPACE);
        } catch (VaultException ex) {
            assertThat(fail).as("only the injected encoding failure may throw").isTrue();
        }
        assertThat(signature)
                .as("the agent's intermediate signature is erased on every path")
                .containsOnly((byte) 0);
    }

    @Test
    void signs_and_verifies_the_exact_bytes() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(signature)
                .startsWith("-----BEGIN SSH SIGNATURE-----")
                .endsWith("-----END SSH SIGNATURE-----\n");
        assertThat(SshSignature.verify(message, signature, key.keyBlob(), NAMESPACE)).isTrue();
    }

    @Test
    void an_agent_key_signature_verifies_with_openssh(@TempDir Path dir) throws Exception {
        final AgentKey signer = key;
        final Path signature = dir.resolve("message.sig");
        final Path allowed = dir.resolve("allowed_signers");
        Files.writeString(signature, SshSignature.sign(signer, message, NAMESPACE));
        Files.writeString(allowed, "person " + key.authorizedKeysLine() + "\n");
        final Process process =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-f",
                                allowed.toString(),
                                "-I",
                                "person",
                                "-n",
                                NAMESPACE,
                                "-s",
                                signature.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(message);
            }
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .as("OpenSSH verification finishes")
                    .isTrue();
            assertThat(process.exitValue())
                    .as("OpenSSH verifies the signature from the signing seam")
                    .isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void one_byte_changed_is_not_the_same_message() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);
        final byte[] altered = "{\"messageId\":\"two\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(SshSignature.verify(altered, signature, key.keyBlob(), NAMESPACE)).isFalse();
    }

    /**
     * The namespace is what stops a signature over one kind of thing being replayed as a signature
     * over another - a message presented as a key list, say.
     */
    @Test
    void a_signature_for_something_else_does_not_verify_here() {
        final String signature = SshSignature.sign(key, message, "porelo-keys");

        assertThat(SshSignature.verify(message, signature, key.keyBlob(), NAMESPACE)).isFalse();
    }

    @Test
    void another_key_does_not_verify() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(
                        SshSignature.verify(
                                message,
                                signature,
                                SigningKey.generate("somebody else").keyBlob(),
                                NAMESPACE))
                .isFalse();
    }

    @Test
    void says_which_key_a_signature_claims_to_be_from() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(SshSignature.signerOf(signature)).isEqualTo(key.keyBlob());
    }

    @Test
    void what_is_not_a_signature_verifies_as_nothing_rather_than_throwing() {
        assertThat(SshSignature.signerOf("not a signature")).isNull();
        assertThat(SshSignature.verify(message, "not a signature", key.keyBlob(), NAMESPACE))
                .isFalse();
        assertThat(
                        SshSignature.verify(
                                message,
                                "-----BEGIN SSH SIGNATURE-----\nAAAA\n-----END SSH SIGNATURE-----\n",
                                key.keyBlob(),
                                NAMESPACE))
                .isFalse();
    }
}
