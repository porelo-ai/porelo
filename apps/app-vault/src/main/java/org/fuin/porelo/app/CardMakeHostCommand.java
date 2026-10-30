package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Writes a host card, signed by its host admin's key, to standard output.
 *
 * <p>Run on the host admin's own machine, where their key is: nothing on the host holds it. The key
 * is read from its file, an unencrypted Ed25519 OpenSSH key, since Porelo asks no ssh-agent for a
 * signature yet. It needs no vault, home or network.
 */
@picocli.CommandLine.Command(
        name = "make-host",
        mixinStandardHelpOptions = true,
        description = "Writes a host card signed by the host admin's key to standard output.")
public class CardMakeHostCommand implements Callable<Integer> {

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
            required = true,
            paramLabel = "FILE",
            description = "The host admin's private key, an unencrypted Ed25519 OpenSSH key file.")
    private Path key = Path.of("");

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final String hostKey;
        final SigningKey signer;
        try {
            hostKey = keyLine(Files.readString(sshHostKey, StandardCharsets.UTF_8));
        } catch (final java.nio.charset.CharacterCodingException ex) {
            return refuse(err, 65, "'" + sshHostKey + "' is not a public key's text");
        } catch (final IOException ex) {
            return refuse(
                    err, 66, "cannot read the host key '" + sshHostKey + "': " + ex.getMessage());
        } catch (final CardException ex) {
            return refuse(err, 65, "'" + sshHostKey + "': " + ex.getMessage());
        }
        try {
            signer =
                    new SigningKey(
                            Base64.getDecoder()
                                    .decode(
                                            OpenSshPrivateKey.seedBase64(
                                                    Files.readString(key, StandardCharsets.UTF_8))),
                            "");
        } catch (final java.nio.charset.CharacterCodingException ex) {
            return refuse(err, 65, "'" + key + "' is not an OpenSSH private key");
        } catch (final IOException ex) {
            return refuse(err, 66, "cannot read the key '" + key + "': " + ex.getMessage());
        } catch (final VaultException ex) {
            // Not the vault's advice: it would have the key's own passphrase taken off in place,
            // and this command keeps nothing in a vault.
            return refuse(
                    err,
                    65,
                    "'"
                            + key
                            + "' is not an unencrypted Ed25519 OpenSSH key, the only kind card"
                            + " make-host signs with: "
                            + String.valueOf(ex.getMessage())
                                    .split("\\. ", 2)[0]
                                    .replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?"));
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
        }
        out.print(card);
        out.flush();
        return 0;
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
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
