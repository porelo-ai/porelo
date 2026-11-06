package org.fuin.porelo.app;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/**
 * Adds a host's read-only deploy key to the organization's repository. The token comes on standard
 * input and nowhere else, and is not kept; a key file's content is never printed, since a file
 * named wrongly may hold the private half.
 */
@Command(
        name = "add-read-key",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Adds a host's read-only deploy key, the token on standard input.")
public class OrgAddReadKeyCommand extends OrgReadKeyCommand {

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final Read read = read(machine);
        final boolean added =
                read.forge().add(read.key(), "porelo host read key " + fingerprint(read.key()));
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", read.forge().revision());
        answer.put("added", added);
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line(Boolean.TRUE.equals(answer.get("added")) ? "Added." : "It was there already.");
    }

    /** As {@code ssh-keygen -l} prints it, of a key of any type a host may have. */
    private static String fingerprint(final String line) {
        final byte[] bytes = Base64.getDecoder().decode(line.substring(line.indexOf(' ') + 1));
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
