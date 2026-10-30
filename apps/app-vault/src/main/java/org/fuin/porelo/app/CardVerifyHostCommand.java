package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Checks a host card against its host admin's key, and writes nothing.
 *
 * <p>For a person's machine about to reach a host: the card says where its ssh, forge and Matrix
 * listen and which key its ssh answers with, so it is taken only when the key trusted for the host
 * admin signed it. It needs no vault, home or network. The answer is a contract for a program: its
 * names are kept.
 */
@picocli.CommandLine.Command(
        name = "verify-host",
        mixinStandardHelpOptions = true,
        description = "Checks a host card against its host admin's key, and writes nothing.")
public class CardVerifyHostCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "CARD", description = "The host card's file.")
    private Path card = Path.of("");

    @Option(
            names = "--signer",
            required = true,
            paramLabel = "FINGERPRINT",
            description = "The key trusted for the host admin, SHA256:...")
    private String signer = "";

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!signer.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            return refuse(err, 65, "'" + printable(signer) + "' is not a key's fingerprint");
        }
        final HostCard checked;
        try {
            checked =
                    HostCard.read(Files.readString(card, StandardCharsets.UTF_8), card.toString());
            checked.checkSignedBy(signer);
        } catch (final CardException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        } catch (final java.nio.charset.CharacterCodingException ex) {
            return refuse(err, 65, "'" + card + "' is not a card's text");
        } catch (final IOException ex) {
            return refuse(
                    err,
                    66,
                    "cannot read '" + printable(card.toString()) + "': " + ex.getMessage());
        }
        final Map<String, Object> forge = new LinkedHashMap<>();
        forge.put("web_port", checked.forgeWebPort());
        forge.put("git_port", checked.forgeGitPort());
        final Map<String, Object> services = new LinkedHashMap<>();
        services.put("forge", forge);
        services.put("matrix", Map.of("port", checked.matrixPort()));
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("host", checked.host());
        answer.put("address", checked.address());
        answer.put("ssh_port", checked.sshPort());
        answer.put("ssh_host_key", checked.sshHostKey());
        answer.put("services", services);
        answer.put("signed_by", signer);
        if (json) {
            out.println(Json.write(answer));
        } else {
            out.println(
                    checked.host()
                            + " at "
                            + checked.address()
                            + ", ssh "
                            + checked.sshPort()
                            + ", forge "
                            + checked.forgeWebPort()
                            + " and "
                            + checked.forgeGitPort()
                            + ", matrix "
                            + checked.matrixPort()
                            + ", signed by "
                            + signer);
        }
        out.flush();
        return 0;
    }

    private static String printable(final String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
