package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Writes a host card, signed by its host admin's key, to standard output.
 *
 * <p>Run on the host admin's own machine, where their key is: nothing on the host holds it. The key
 * is read from its file, an unencrypted Ed25519 OpenSSH key, or it signs through the ssh-agent
 * {@code SSH_AUTH_SOCK} names, KeePassXC's included, so a key kept only there is never written to
 * disk. Both make the same card, Ed25519 being deterministic. It needs no vault, home or network.
 */
@picocli.CommandLine.Command(
        name = "make-host",
        mixinStandardHelpOptions = true,
        description = "Writes a host card signed by the host admin's key to standard output.")
public class CardMakeHostCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** A public key's line is short; a file larger than this is no {@code .pub} file. */
    private static final int KEY_FILE_LIMIT = 16 * 1024;

    private static final String FINGERPRINT = "SHA256:[A-Za-z0-9+/]{43}";

    @Option(
            names = "--host",
            required = true,
            paramLabel = "NAME",
            description = "The host's name, a lower-case DNS label.")
    private String host = "";

    @Option(
            names = "--address",
            required = true,
            paramLabel = "ADDRESS",
            description = "Where the host is reached, a host name or an IP address.")
    private String address = "";

    @Option(
            names = "--ssh-port",
            required = true,
            paramLabel = "PORT",
            description = "The port the host's ssh listens on.")
    private int sshPort;

    @Option(
            names = "--ssh-host-key",
            required = true,
            paramLabel = "FILE",
            description = "The host's public ssh key, such as /etc/ssh/ssh_host_ed25519_key.pub.")
    private Path sshHostKey = Path.of("");

    @Option(
            names = "--forge-web-port",
            required = true,
            paramLabel = "PORT",
            description = "The port of the forge's web interface.")
    private int forgeWebPort;

    @Option(
            names = "--forge-git-port",
            required = true,
            paramLabel = "PORT",
            description = "The port of the forge's git over ssh.")
    private int forgeGitPort;

    @Option(
            names = "--matrix-port",
            required = true,
            paramLabel = "PORT",
            description = "The port of the host's Matrix server.")
    private int matrixPort;

    @Option(
            names = "--key",
            paramLabel = "FILE",
            description = "The host admin's private key, an unencrypted Ed25519 OpenSSH key file.")
    private @Nullable Path key;

    @Option(
            names = "--agent-key",
            paramLabel = "FINGERPRINT|PUBFILE",
            description =
                    "The host admin's Ed25519 key in the ssh-agent SSH_AUTH_SOCK names, by its"
                            + " SHA256 fingerprint or by its .pub file.")
    private @Nullable String agentKey;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Path keyFile = key;
        final String agentNamed = agentKey;
        if ((keyFile == null) == (agentNamed == null)) {
            return refuse(
                    err,
                    64,
                    "card make-host signs with exactly one of --key FILE and --agent-key"
                            + " FINGERPRINT|PUBFILE");
        }
        final String hostKey;
        final AgentKey signer;
        try {
            hostKey = keyLine(keyFileText(sshHostKey));
        } catch (final java.nio.charset.CharacterCodingException | TooLarge ex) {
            return refuse(err, 65, "'" + sshHostKey + "' is not a public key's text");
        } catch (final IOException ex) {
            return refuse(
                    err, 66, "cannot read the host key '" + sshHostKey + "': " + ex.getMessage());
        } catch (final CardException ex) {
            return refuse(err, 65, "'" + sshHostKey + "': " + ex.getMessage());
        }
        if (agentNamed != null) {
            try {
                signer = agentSigner(agentNamed);
            } catch (final Refusal ex) {
                return refuse(err, ex.code, String.valueOf(ex.getMessage()));
            }
        } else {
            try {
                signer = fileSigner(java.util.Objects.requireNonNull(keyFile, "--key"));
            } catch (final Refusal ex) {
                return refuse(err, ex.code, String.valueOf(ex.getMessage()));
            }
        }
        final String card;
        try {
            card =
                    HostCard.write(
                            host,
                            address,
                            sshPort,
                            hostKey,
                            forgeWebPort,
                            forgeGitPort,
                            matrixPort,
                            signer);
        } catch (final CardException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        } catch (final VaultException ex) {
            // Only an agent's key fails here: a key file's seed signs without asking anyone.
            return refuse(
                    err,
                    65,
                    "the ssh agent did not sign with "
                            + fingerprint(signer.keyBlob())
                            + ": "
                            + printable(String.valueOf(ex.getMessage())));
        }
        // Whatever signed it, the card goes out only as its key's: an agent may answer with another
        // key's signature, or with bytes that verify as nothing.
        final java.util.Optional<String> unverified =
                unverified(card, fingerprint(signer.keyBlob()));
        if (unverified.isPresent()) {
            return refuse(err, 65, unverified.get());
        }
        out.print(card);
        out.flush();
        return 0;
    }

    /** The key read from an unencrypted Ed25519 OpenSSH key file. */
    private static SigningKey fileSigner(final Path key) {
        try {
            return new SigningKey(
                    Base64.getDecoder().decode(OpenSshPrivateKey.seedBase64(keyFileText(key))), "");
        } catch (final java.nio.charset.CharacterCodingException | TooLarge ex) {
            throw new Refusal(65, "'" + key + "' is not an OpenSSH private key");
        } catch (final IOException ex) {
            throw new Refusal(66, "cannot read the key '" + key + "': " + ex.getMessage());
        } catch (final VaultException ex) {
            // Not the vault's advice: it would have the key's own passphrase taken off in place,
            // and this command keeps nothing in a vault.
            throw new Refusal(
                    65,
                    "'"
                            + key
                            + "' is not an unencrypted Ed25519 OpenSSH key, the only kind card"
                            + " make-host signs with: "
                            + printable(String.valueOf(ex.getMessage()).split("\\. ", 2)[0]));
        }
    }

    /**
     * The agent's key named by its fingerprint or by its {@code .pub} file, as an Ed25519 key.
     *
     * <p>The key is chosen by its fingerprint alone, and only the fingerprint is named: the comment
     * is the agent's to say, and would reach the terminal from outside.
     */
    private AgentKey agentSigner(final String named) {
        final String wanted = wantedFingerprint(named);
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (socket == null || socket.isBlank()) {
            throw new Refusal(
                    65, "no ssh agent answers for " + wanted + ": SSH_AUTH_SOCK is not set");
        }
        final List<AgentKey> keys;
        try {
            keys = new SshAgentClient(Path.of(socket)).identities();
        } catch (final VaultException | InvalidPathException ex) {
            throw new Refusal(
                    65,
                    "no ssh agent answers for "
                            + wanted
                            + " at SSH_AUTH_SOCK: "
                            + printable(String.valueOf(ex.getMessage())));
        }
        for (final AgentKey candidate : keys) {
            if (fingerprint(candidate.keyBlob()).equals(wanted)) {
                if (!candidate.authorizedKeysLine().startsWith("ssh-ed25519 ")) {
                    throw new Refusal(
                            65,
                            wanted
                                    + " in the ssh agent is not an Ed25519 key, the only kind card"
                                    + " make-host signs with");
                }
                return candidate;
            }
        }
        throw new Refusal(65, "the ssh agent does not hold the key " + wanted);
    }

    /** The fingerprint given, or that of the one key in the {@code .pub} file named. */
    private static String wantedFingerprint(final String named) {
        if (named.startsWith("SHA256:")) {
            if (!named.matches(FINGERPRINT)) {
                throw new Refusal(
                        64,
                        "'"
                                + printable(named)
                                + "' is not a key's fingerprint, SHA256: and 43 characters");
            }
            return named;
        }
        final String line;
        try {
            line = keyLine(keyFileText(Path.of(named)));
        } catch (final java.nio.charset.CharacterCodingException | TooLarge ex) {
            throw new Refusal(65, "'" + printable(named) + "' is not a public key's text");
        } catch (final IOException | InvalidPathException ex) {
            throw new Refusal(
                    66,
                    "cannot read the public key '"
                            + printable(named)
                            + "': "
                            + printable(String.valueOf(ex.getMessage())));
        } catch (final CardException ex) {
            throw new Refusal(65, "'" + printable(named) + "': " + ex.getMessage());
        }
        final String[] parts = line.split(" ");
        try {
            if (parts.length == 2) {
                return fingerprint(Base64.getDecoder().decode(parts[1]));
            }
        } catch (final IllegalArgumentException ex) {
            // Falls through to the refusal: a key that is no Base64 is no key.
        }
        throw new Refusal(65, "'" + printable(named) + "' is not a public key's line");
    }

    /**
     * Says why a card made is not one its signer's key signed, or nothing when it is.
     *
     * @param card The card's text.
     * @param fingerprint The key it must be signed by.
     * @return Why not, or empty.
     */
    static java.util.Optional<String> unverified(final String card, final String fingerprint) {
        try {
            HostCard.read(card, "the card made").checkSignedBy(fingerprint);
            return java.util.Optional.empty();
        } catch (final CardException ex) {
            return java.util.Optional.of(
                    "the card made does not verify as signed by "
                            + fingerprint
                            + ": "
                            + ex.getMessage());
        }
    }

    /** A file too large to be a key: no key's text, though it could be read. */
    private static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** A key file's text: a regular file named itself, not through a link, and small. */
    private static String keyFileText(final Path file) throws IOException {
        final java.nio.file.attribute.BasicFileAttributes seen =
                Files.readAttributes(
                        file,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!seen.isRegularFile()) {
            throw new IOException("it is no regular file");
        }
        try (java.io.InputStream in =
                Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = in.readNBytes(KEY_FILE_LIMIT + 1);
            if (bytes.length > KEY_FILE_LIMIT) {
                throw new TooLarge();
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    private static String fingerprint(final byte[] blob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** A text from outside, its control and format characters shown as {@code ?}. */
    private static String printable(final String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]", "?");
    }

    /**
     * The type and the key of a {@code .pub} file's line, without the comment a card has no use
     * for.
     */
    private static String keyLine(final String text) {
        final List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        // One key: a file of several would have the first signed and the rest dropped unsaid.
        if (lines.size() != 1) {
            throw new CardException("holds " + lines.size() + " keys, not one");
        }
        final String[] parts = lines.getFirst().strip().split(" ", 3);
        return parts.length < 2 ? lines.getFirst().strip() : parts[0] + " " + parts[1];
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        // Every refusal's words, file names and the platform's messages among them.
        err.println("porelo: " + printable(message));
        err.flush();
        return code;
    }

    /** A refusal and its exit code, from the steps that pick the key to sign with. */
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int code;

        private Refusal(final int code, final String message) {
            super(message);
            this.code = code;
        }
    }
}
