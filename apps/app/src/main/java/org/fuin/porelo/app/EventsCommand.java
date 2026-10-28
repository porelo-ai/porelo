package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Shows this account's event log, and checks it against the user's journal. */
@Command(
        name = "events",
        mixinStandardHelpOptions = true,
        description =
                "Shows every security-relevant event of this account - approvals, clearances,"
                        + " hand-ins, credentials, project files applied - and with --verify checks"
                        + " that none was changed or removed afterwards.")
public class EventsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--since",
            paramLabel = "<instant>",
            description = "Only events at or after this time, as 2026-10-28T03:00:00Z.")
    private @Nullable String since;

    @Option(
            names = "--verify",
            description =
                    "Checks the log's chain and every line against the hashes the user's journal"
                            + " holds; exit 1 when something was changed or removed.")
    private boolean verify;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final EventLog log = EventLog.of(context);
        if (verify) {
            final EventLog.Verification found = log.verify();
            out.println("log       " + context.paths().eventLog());
            out.println("lines     " + found.lines());
            if (found.firstBroken() > 0) {
                out.println("CHANGED   the chain breaks at line " + found.firstBroken());
            }
            if (!found.anchorRead()) {
                out.println(
                        "UNKNOWN   the user's journal could not be read, so nothing is checked"
                                + " against it");
            } else {
                found.notAnchored()
                        .forEach(
                                number ->
                                        out.println(
                                                "UNANCHORED line "
                                                        + number
                                                        + " has no hash in the journal"));
                found.missing()
                        .forEach(
                                hash ->
                                        out.println(
                                                "MISSING   the journal holds "
                                                        + hash
                                                        + ", which no line has: one was changed or"
                                                        + " removed"));
                if (found.beforeAnchor() > 0) {
                    out.println(
                            "older     "
                                    + found.beforeAnchor()
                                    + " line(s) ahead of the first the journal holds and older than"
                                    + " its oldest of this log, vouched for by the chain alone");
                }
            }
            out.println(found.holds() ? "holds" : "does not hold");
            out.flush();
            return found.holds() ? 0 : 1;
        }
        final Instant from;
        try {
            from = since == null ? null : Instant.parse(since);
        } catch (DateTimeParseException ex) {
            err.println("porelo: --since takes an instant such as 2026-10-28T03:00:00Z");
            err.flush();
            return 2;
        }
        final List<Map<String, Object>> entries = log.entries(from);
        if (entries.isEmpty()) {
            out.println("No events.");
        }
        for (final Map<String, Object> entry : entries) {
            out.println(
                    entry.get("at")
                            + "  "
                            + entry.get("kind")
                            + "  "
                            + (String.valueOf(entry.get("project")).isEmpty()
                                    ? ""
                                    : entry.get("project") + "  ")
                            + (String.valueOf(entry.get("task")).isEmpty()
                                    ? ""
                                    : entry.get("task") + "  ")
                            + entry.get("detail"));
        }
        out.flush();
        return 0;
    }
}
