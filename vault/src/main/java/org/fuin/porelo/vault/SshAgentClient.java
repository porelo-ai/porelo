package org.fuin.porelo.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Requests identities and signatures from an SSH agent. */
public final class SshAgentClient {

    private final Path socketPath;

    /**
     * Uses the given Unix socket.
     *
     * @param socketPath The path from {@code SSH_AUTH_SOCK}.
     */
    public SshAgentClient(Path socketPath) {
        this.socketPath = socketPath;
    }

    /**
     * Returns the keys offered by the agent.
     *
     * @return Keys whose signing requests go back to this socket.
     */
    public List<AgentKey> identities() {
        final ByteBuffer reply =
                exchange(new byte[] {SshAgentServer.SSH_AGENTC_REQUEST_IDENTITIES});
        expect(reply, SshAgentServer.SSH_AGENT_IDENTITIES_ANSWER, "list identities");
        if (reply.remaining() < 4) {
            throw new SshProtocolException("Truncated agent identity count");
        }
        final int count = reply.getInt();
        if (count < 0 || count > reply.remaining() / 8) {
            throw new SshProtocolException("Implausible agent identity count");
        }
        final List<AgentKey> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            final byte[] blob = SshWire.readString(reply);
            final String algorithm =
                    new String(
                            SshWire.readString(ByteBuffer.wrap(blob)), StandardCharsets.US_ASCII);
            final String comment = new String(SshWire.readString(reply), StandardCharsets.UTF_8);
            keys.add(new RemoteKey(blob, algorithm, comment));
        }
        exhausted(reply);
        return List.copyOf(keys);
    }

    /**
     * Selects the key with the given SHA256 fingerprint.
     *
     * @param fingerprint OpenSSH's {@code SHA256:} fingerprint, without Base64 padding.
     * @return The matching key.
     * @throws VaultException If the agent has no such key or refuses the request.
     */
    public AgentKey key(String fingerprint) {
        for (final AgentKey key : identities()) {
            if (fingerprint.equals(fingerprint(key.keyBlob()))) {
                return key;
            }
        }
        throw new VaultException("The SSH agent has no key with the requested SHA256 fingerprint");
    }

    private static String fingerprint(byte[] keyBlob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private byte[] sign(byte[] keyBlob, byte[] data, int flags) {
        if (13L + keyBlob.length + data.length > SshAgentServer.MAX_MESSAGE) {
            throw new SshProtocolException("Agent sign request exceeds the message limit");
        }
        try {
            final ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(SshAgentServer.SSH_AGENTC_SIGN_REQUEST);
            SshWire.writeString(request, keyBlob);
            SshWire.writeString(request, data);
            request.write(ByteBuffer.allocate(4).putInt(flags).array());
            final ByteBuffer reply = exchange(request.toByteArray());
            return readSignature(reply);
        } catch (IOException ex) {
            throw new VaultException("Cannot encode the agent sign request", ex);
        }
    }

    static byte[] readSignature(ByteBuffer reply) {
        byte[] signature = null;
        boolean returned = false;
        try {
            expect(reply, SshAgentServer.SSH_AGENT_SIGN_RESPONSE, "sign");
            signature = SshWire.readString(reply);
            exhausted(reply);
            final ByteBuffer blob = ByteBuffer.wrap(signature);
            if (SshWire.readString(blob).length == 0 || blob.remaining() < 4) {
                throw new SshProtocolException("Empty agent signature");
            }
            final int length = blob.getInt();
            if (length <= 0 || length != blob.remaining()) {
                throw new SshProtocolException("Invalid agent signature length");
            }
            returned = true;
            return signature;
        } finally {
            java.util.Arrays.fill(reply.array(), (byte) 0);
            if (!returned && signature != null) java.util.Arrays.fill(signature, (byte) 0);
        }
    }

    private ByteBuffer exchange(byte[] request) {
        ByteBuffer reply = null;
        boolean received = false;
        try {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socketPath));
                final ByteBuffer frame = ByteBuffer.allocate(4 + request.length);
                frame.putInt(request.length).put(request).flip();
                while (frame.hasRemaining()) channel.write(frame);
                final ByteBuffer header = ByteBuffer.allocate(4);
                readFully(channel, header);
                final int length = header.flip().getInt();
                if (length <= 0 || length > SshAgentServer.MAX_MESSAGE) {
                    throw new SshProtocolException("Implausible agent message length " + length);
                }
                reply = ByteBuffer.allocate(length);
                readFully(channel, reply);
            }
            received = true;
            return java.util.Objects.requireNonNull(reply, "A complete exchange has a reply")
                    .flip();
        } catch (IOException ex) {
            throw new VaultException("Cannot communicate with the SSH agent", ex);
        } finally {
            if (!received && reply != null) java.util.Arrays.fill(reply.array(), (byte) 0);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new SshProtocolException("Truncated agent reply");
            }
        }
    }

    private static void expect(ByteBuffer reply, byte expected, String action) {
        final byte type = reply.get();
        if (type == SshAgentServer.SSH_AGENT_FAILURE) {
            exhausted(reply);
            throw new VaultException("The SSH agent refused to " + action);
        }
        if (type != expected) {
            throw new SshProtocolException(
                    "Unexpected agent reply type " + Byte.toUnsignedInt(type));
        }
    }

    private static void exhausted(ByteBuffer reply) {
        if (reply.hasRemaining()) {
            throw new SshProtocolException("Trailing data in the agent reply");
        }
    }

    private final class RemoteKey implements AgentKey {

        private final byte[] blob;

        private final String algorithm;

        private final String comment;

        private RemoteKey(byte[] blob, String algorithm, String comment) {
            this.blob = blob;
            this.algorithm = algorithm;
            this.comment = comment;
        }

        @Override
        public String comment() {
            return comment;
        }

        @Override
        public byte[] keyBlob() {
            return blob.clone();
        }

        @Override
        public String authorizedKeysLine() {
            return algorithm + " " + Base64.getEncoder().encodeToString(blob) + " " + comment;
        }

        @Override
        public byte[] sign(byte[] data, int flags) {
            return SshAgentClient.this.sign(blob, data, flags);
        }
    }
}
