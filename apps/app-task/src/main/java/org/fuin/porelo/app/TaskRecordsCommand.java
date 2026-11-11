package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Answers a project's task records since a time, removed tasks' among them: what each task worked
 * on, from when to when, with which agent, model and tokens, and its tool calls.
 *
 * <p>The same answer as the daemon's {@code Records}, from the same code.
 */
@Command(
        name = "records",
        mixinStandardHelpOptions = true,
        description =
                "A project's task records since a time, removed tasks' among them, oldest first:"
                        + " work, times, agent, model, token totals and tool calls.")
public class TaskRecordsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = {"-p", "--project"},
            required = true,
            paramLabel = "<name>",
            description = "The project.")
    private String project = "";

    @Option(
            names = "--since",
            required = true,
            paramLabel = "<time>",
            description =
                    "The earliest end answered, ISO-8601 (2026-11-11T08:00:00Z, or a date in this"
                            + " machine's zone); a task not yet ended is answered.")
    private String since = "";

    @Option(names = "--json", description = "Answers a program: a JSON array of the records.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Instant from = TaskWorkRecords.parseSince(since);
        if (from == null) {
            err.println(
                    "porelo: --since takes an ISO-8601 time or date, such as 2026-11-11T08:00:00Z,"
                            + " not '"
                            + since
                            + "'");
            err.flush();
            return 64;
        }
        final List<Map<String, Object>> records;
        try {
            records = new TaskWorkRecords(context).since(project, from);
        } catch (IOException ex) {
            err.println("porelo: the task records could not be read: " + ex.getMessage());
            err.flush();
            return 74;
        }
        if (json) {
            out.println(Json.write(records));
        } else {
            for (final Map<String, Object> record : records) {
                out.println(line(record));
            }
        }
        out.flush();
        return 0;
    }

    private static String line(final Map<String, Object> record) {
        final String work =
                record.get("work") instanceof Map<?, ?> given
                        ? given.get("issue") + ":" + given.get("phase")
                        : "-";
        final int calls = record.get("toolCalls") instanceof List<?> list ? list.size() : 0;
        return record.get("startedAt")
                + "  "
                + (record.get("endedAt") == null ? "running" : record.get("endedAt"))
                + "  "
                + (record.get("end") == null ? "-" : record.get("end"))
                + "  "
                + record.get("task")
                + "  "
                + work
                + "  "
                + calls
                + " tool calls";
    }
}
