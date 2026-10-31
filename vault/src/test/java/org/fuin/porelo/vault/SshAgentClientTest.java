package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that agent input cannot choose another key or turn a frame length into an allocation. */
@Timeout(15)
class SshAgentClientTest {

    private final SigningKey key = SigningKey.generate("porelo-test");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void erasesTheSignatureReplyOnSuccessAndMalformedInput(boolean trailing) {
        final byte[] signed = SshWire.ed25519SignatureBlob(new byte[64]);
        final var reply = java.nio.ByteBuffer.allocate(5 + signed.length + (trailing ? 1 : 0));
        reply.put(SshAgentServer.SSH_AGENT_SIGN_RESPONSE).putInt(signed.length).put(signed);
        if (trailing) reply.put((byte) 23);
        reply.flip();
        if (trailing) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> SshAgentClient.readSignature(reply))
                    .as("a malformed reply cannot release a signature")
                    .isInstanceOf(SshProtocolException.class);
        } else {
            assertThat(SshAgentClient.readSignature(reply))
                    .as("the caller receives a separate usable signature")
                    .isEqualTo(signed);
        }
        assertThat(reply.array())
                .as("the transport's signature bytes are cleared on every path")
                .containsOnly((byte) 0);
    }

    @Test
    void selects_the_requested_key_among_several_identities(@TempDir Path dir) throws Exception {
        final SigningKey other = SigningKey.generate("other");
        try (SshAgentServer server =
                new SshAgentServer(dir.resolve("agent.sock"), List.of(other, key))) {
            Thread.ofVirtual().start(server);
            final SshAgentClient client = new SshAgentClient(server.socketPath());
            assertThat(client.identities()).as("all offered keys").hasSize(2);
            final AgentKey selected = client.key(fingerprint(key));
            final byte[] data = "exact bytes".getBytes(StandardCharsets.UTF_8);

            assertThat(selected.keyBlob())
                    .as("selection uses the SHA256 fingerprint")
                    .isEqualTo(key.keyBlob());
            assertThat(key.verify(data, selected.sign(data, 0)))
                    .as("the selected key signed")
                    .isTrue();
        }
    }

    @Test
    void refuses_an_agent_without_the_requested_key(@TempDir Path dir) throws Exception {
        try (SshAgentServer server = new SshAgentServer(dir.resolve("agent.sock"), List.of())) {
            Thread.ofVirtual().start(server);
            assertThatThrownBy(() -> new SshAgentClient(server.socketPath()).key(fingerprint(key)))
                    .as("an absent key must never fall back to another identity")
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("no key");
        }
    }

    @Test
    void reports_a_refused_signature(@TempDir Path dir) throws Exception {
        try (SshAgentServer server =
                new SshAgentServer(dir.resolve("agent.sock"), List.of(key)) {
                    @Override
                    byte[] handle(byte[] request) {
                        return request[0] == SSH_AGENTC_SIGN_REQUEST
                                ? new byte[] {SSH_AGENT_FAILURE}
                                : super.handle(request);
                    }
                }) {
            Thread.ofVirtual().start(server);
            final AgentKey selected = new SshAgentClient(server.socketPath()).key(fingerprint(key));
            assertThatThrownBy(() -> selected.sign(new byte[] {1}, 0))
                    .as("a confirmation refusal must reach the caller")
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("refused");
        }
    }

    @Test
    void produces_the_same_sshsig_as_openssh_and_verifies_there(@TempDir Path dir)
            throws Exception {
        try (SshAgentServer server = new SshAgentServer(dir.resolve("agent.sock"), List.of(key))) {
            Thread.ofVirtual().start(server);
            final AgentKey selected = new SshAgentClient(server.socketPath()).key(fingerprint(key));
            final byte[] message = "a message with UTF-8: é\n".getBytes(StandardCharsets.UTF_8);
            final String signature = SshSignature.sign(selected, message, "porelo-vault");
            final Path publicKey = dir.resolve("identity.pub");
            Files.writeString(publicKey, key.authorizedKeysLine());
            final ProcessBuilder sign =
                    new ProcessBuilder(
                            "ssh-keygen",
                            "-Y",
                            "sign",
                            "-f",
                            publicKey.toString(),
                            "-n",
                            "porelo-vault");
            sign.environment().put("SSH_AUTH_SOCK", server.socketPath().toString());
            final Process signing = sign.redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = signing.getOutputStream()) {
                input.write(message);
            }
            final String openssh =
                    new String(signing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(signing.waitFor()).as("ssh-keygen signing exit").isZero();
            assertThat(signature.equals(openssh)).as("SSHSIG bytes match OpenSSH").isTrue();

            final Path signatureFile = dir.resolve("message.sig");
            final Path allowed = dir.resolve("allowed_signers");
            Files.writeString(signatureFile, signature);
            Files.writeString(allowed, "person " + key.authorizedKeysLine() + "\n");
            final Process verifying =
                    new ProcessBuilder(
                                    "ssh-keygen",
                                    "-Y",
                                    "verify",
                                    "-f",
                                    allowed.toString(),
                                    "-I",
                                    "person",
                                    "-n",
                                    "porelo-vault",
                                    "-s",
                                    signatureFile.toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            try (var input = verifying.getOutputStream()) {
                input.write(message);
            }
            assertThat(verifying.waitFor())
                    .as("ssh-keygen verifies the agent's signature")
                    .isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 262145, Integer.MAX_VALUE})
    void refuses_unbounded_reply_frames(int length, @TempDir Path dir) throws Exception {
        withReply(
                dir,
                ByteBuffer.allocate(4).putInt(length).array(),
                client ->
                        assertThatThrownBy(client::identities)
                                .as("frame length is bounded before allocation")
                                .isInstanceOf(SshProtocolException.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0000",
                "000000050c00",
                "000000010c",
                "000000050cffffffff",
                "000000050c00000001",
                "000000020500",
                "000000010e"
            })
    void refuses_truncated_or_malformed_replies(String reply, @TempDir Path dir) throws Exception {
        withReply(
                dir,
                java.util.HexFormat.of().parseHex(reply),
                client ->
                        assertThatThrownBy(client::identities)
                                .as("malformed replies are never an empty key list")
                                .isInstanceOf(SshProtocolException.class));
    }

    @Test
    void accepts_a_fragmented_empty_identity_list(@TempDir Path dir) throws Exception {
        withReply(
                dir,
                java.util.HexFormat.of().parseHex("000000050c00000000"),
                client ->
                        assertThat(client.identities())
                                .as("zero identities is a valid answer")
                                .isEmpty());
    }

    static String fingerprint(AgentKey key) throws Exception {
        return "SHA256:"
                + Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.keyBlob()));
    }

    private static void withReply(Path dir, byte[] reply, Consumer<SshAgentClient> assertion)
            throws Exception {
        final Path socket = dir.resolve("agent.sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            final var peer =
                    executor.submit(
                            () -> {
                                try (var channel = server.accept()) {
                                    final var input =
                                            new DataInputStream(Channels.newInputStream(channel));
                                    input.readNBytes(input.readInt());
                                    final var output = Channels.newOutputStream(channel);
                                    for (byte value : reply) {
                                        output.write(value);
                                        output.flush();
                                    }
                                }
                                return null;
                            });
            assertion.accept(new SshAgentClient(socket));
            peer.get(5, TimeUnit.SECONDS);
        }
    }
}
