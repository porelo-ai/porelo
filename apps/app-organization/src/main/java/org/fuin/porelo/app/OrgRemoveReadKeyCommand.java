package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/**
 * Removes a host's deploy key from the organization's repository using a token on standard input.
 */
@Command(
        name = "remove-read-key",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Removes a host's read-only deploy key, the token on standard input.")
public class OrgRemoveReadKeyCommand extends OrgReadKeyCommand {

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final Read read = read(machine);
        final boolean removed = read.forge().remove(read.key());
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", read.forge().revision());
        answer.put("removed", removed);
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line(Boolean.TRUE.equals(answer.get("removed")) ? "Removed." : "It was not there.");
    }
}
