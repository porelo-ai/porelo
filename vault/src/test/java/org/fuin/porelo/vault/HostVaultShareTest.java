package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Tests that a vault share stays reproducible and belongs to one host, account and key. */
@Timeout(15)
class HostVaultShareTest {

    @Test
    void anUnexpectedVerificationFailureErasesTheSignature() {
        final byte[] message = new byte[] {1};
        final byte[] signature = SshSignature.signBlob(key, message, "porelo-vault");
        assertThatThrownBy(() -> HostVaultShare.deriveVerified(null, signature, key.keyBlob()))
                .as("a verification exception must still erase the signature")
                .isInstanceOf(RuntimeException.class);
        assertThat(signature)
                .as("no signature remains on an exceptional path")
                .containsOnly((byte) 0);
    }

    private static final String HOST = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final SigningKey key =
            new SigningKey(
                    HexFormat.of()
                            .parseHex(
                                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                    "fixed-test-key");

    @Test
    void derives_the_fixed_vector_from_the_binary_sshsig() {
        final byte[] share = HostVaultShare.derive(key, HOST, "alice");

        assertThat(share.length).as("vault share length").isEqualTo(32);
        assertThat(
                        Arrays.equals(
                                share,
                                HexFormat.of()
                                        .parseHex(
                                                "37d98af5cde2700326ec011de7b4fbc565ff162ae51e419ce92e532ea6ef801c")))
                .as("fixed Ed25519 SSHSIG and HKDF-SHA256 vector")
                .isTrue();
        assertThat(Arrays.equals(share, HostVaultShare.derive(key, HOST, "alice")))
                .as("Ed25519 signing is deterministic")
                .isTrue();
    }

    @Test
    void separates_hosts_accounts_and_keys() {
        final SigningKey other = SigningKey.generate("other");
        final byte[] share = HostVaultShare.derive(key, HOST, "alice");

        assertThat(
                        Arrays.equals(
                                share,
                                HostVaultShare.derive(
                                        key,
                                        "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA",
                                        "alice")))
                .as("another host cannot open this vault")
                .isFalse();
        assertThat(Arrays.equals(share, HostVaultShare.derive(key, HOST, "bob")))
                .as("another account cannot open this vault")
                .isFalse();
        assertThat(Arrays.equals(share, HostVaultShare.derive(other, HOST, "alice")))
                .as("another key cannot open this vault")
                .isFalse();
    }

    @Test
    void derives_the_same_share_through_the_agent(@TempDir Path dir) throws Exception {
        try (SshAgentServer server = new SshAgentServer(dir.resolve("agent.sock"), List.of(key))) {
            Thread.ofVirtual().start(server);
            final AgentKey selected =
                    new SshAgentClient(server.socketPath())
                            .key(SshAgentClientTest.fingerprint(key));
            assertThat(
                            Arrays.equals(
                                    HostVaultShare.derive(selected, HOST, "alice"),
                                    HostVaultShare.derive(key, HOST, "alice")))
                    .as("the agent and local signer derive the same share")
                    .isTrue();
        }
    }

    @Test
    void refuses_a_key_that_cannot_produce_a_deterministic_ed25519_share() throws Exception {
        final var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final var pair = generator.generateKeyPair();
        final var publicKey = (java.security.interfaces.RSAPublicKey) pair.getPublic();
        final AgentKey rsa =
                new RsaKey(
                        (java.security.interfaces.RSAPrivateKey) pair.getPrivate(),
                        publicKey.getModulus(),
                        publicKey.getPublicExponent(),
                        "test");
        assertThatThrownBy(() -> HostVaultShare.derive(rsa, HOST, "alice"))
                .as("the vault share requires an Ed25519 key")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("Ed25519");
    }

    @Test
    void refuses_a_key_blob_with_bytes_after_the_key() {
        final byte[] canonical = key.keyBlob();
        final byte[] padded = Arrays.copyOf(canonical, canonical.length + 1);
        // An agent naming the right key with a byte more: the share would differ from the one an
        // honest agent derives, and the vault would answer another key.
        final AgentKey odd =
                new AgentKey() {
                    @Override
                    public String comment() {
                        return "odd";
                    }

                    @Override
                    public byte[] keyBlob() {
                        return padded.clone();
                    }

                    @Override
                    public String authorizedKeysLine() {
                        return "";
                    }

                    @Override
                    public byte[] sign(final byte[] data, final int flags) {
                        return key.sign(data, flags);
                    }
                };

        assertThatThrownBy(() -> HostVaultShare.derive(odd, HOST, "alice"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("Ed25519");
        assertThatThrownBy(() -> SshSignature.signBlob(odd, new byte[] {1}, "porelo-vault"))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void an_agent_that_hangs_up_is_a_vault_error(@TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("mute.sock");
        try (var server =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(socket));
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var client = server.accept()) {
                                    client.read(java.nio.ByteBuffer.allocate(64));
                                } catch (final java.io.IOException ex) {
                                    // The test only needs the hang-up.
                                }
                            });

            assertThatThrownBy(() -> new SshAgentClient(socket).identities())
                    .isInstanceOf(VaultException.class);
        }
    }

    @Test
    void a_socket_path_too_long_for_unix_is_a_vault_error() {
        assertThatThrownBy(() -> new SshAgentClient(Path.of("/" + "x".repeat(200))).identities())
                .isInstanceOf(VaultException.class);
    }
}
