package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.wire.LimitFactors;

/**
 * How the message filter is run on the way in, shared by every inbound check so each blocks, is
 * handed the factors first and finds its messages where the others do.
 */
final class InboundFilterRun {

    private InboundFilterRun() {}

    /**
     * Returns the filter's command for a check area, in blocking mode.
     *
     * @param filter The filter's executable.
     * @param limits The factors of the account that runs it, handed first.
     * @param check The mailbox-shaped tree the message waits in.
     * @param extra Further arguments, after the shared ones.
     * @return The command.
     */
    static Command command(
            final Path filter, final LimitFactors limits, final Path check, final String... extra) {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                filter.toString(),
                                LimitFactors.OPTION,
                                limits.line(),
                                "--mail",
                                check.toString(),
                                "--blocking",
                                // No settle: what is checked here was put in place by rename.
                                "--stabilityDelayMillis",
                                "0"));
        arguments.addAll(List.of(extra));
        return HelperEnvironment.chosen(Command.of(arguments));
    }

    /**
     * Whether the filter ran to its end: it accepted everything or refused something.
     *
     * @param exitCode Its exit code.
     * @return {@code true} for either ordinary outcome.
     */
    static boolean ran(final int exitCode) {
        return exitCode == MessageFiltering.ALL_ACCEPTED
                || exitCode == MessageFiltering.SOMETHING_REFUSED;
    }

    /**
     * Empties a mailbox's check area, before and after a check, so nothing a crash left is ever
     * taken for this message's verdict.
     *
     * @param mailbox The mailbox whose check area it is.
     * @throws IOException Deleting failed.
     */
    static void clear(final Mailbox mailbox) throws IOException {
        for (final Path directory :
                List.of(
                        mailbox.checkIncoming(),
                        mailbox.checkAccepted(),
                        mailbox.checkRejected(),
                        mailbox.checkFeedback(),
                        mailbox.checkError())) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                for (final Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
