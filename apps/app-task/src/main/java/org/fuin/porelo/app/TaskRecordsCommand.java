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
 * on, from when to when, with which agent, model and tokens, its tool calls and what it reported
 * back.
 *
 * <p>The same records as the daemon's {@code Records}, from the same code; each also carries its
 * task's reports, as {@code Reports} answers them, which a record of the daemon's leaves to {@code
 * Reports}.
 */
@Command(
        name = "records",
        mixinStandardHelpOptions = true,
        description =
                "A project's task records since a time, removed tasks' among them, oldest first:"
                        + " work, times, agent, model, token totals, tool calls and reports.")
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
            final TaskReports reports = new TaskReports(context.paths());
            for (final Map<String, Object> record : records) {
                // Each task's own, told from an earlier task of its name by its record's start.
                record.put(
                        "reports",
                        record.get("task") instanceof String task && TaskResult.named(task)
                                ? reports
                                        .ofRecord(
                                                task,
                                                record.get("startedAt") instanceof String startedAt
                                                        ? startedAt
                                                        : null)
                                        .stream()
                                        .map(TaskReports.Report::asMap)
                                        .toList()
                                : List.of());
            }
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
        // Each phase quoted as JSON quotes it: a phase may hold a comma, a colon or two spaces.
        final String work =
                record.get("work") instanceof List<?> given && !given.isEmpty()
                        ? String.join(
                                ",",
                                given.stream()
                                        .map(
                                                pair ->
                                                        pair instanceof Map<?, ?> fields
                                                                ? fields.get("issue")
                                                                        + ":"
                                                                        + Json.write(
                                                                                String.valueOf(
                                                                                        fields.get(
                                                                                                "phase")))
                                                                : "?")
                                        .toList())
                        : "-";
        final int calls = record.get("toolCalls") instanceof List<?> list ? list.size() : 0;
        final int reported = record.get("reports") instanceof List<?> list ? list.size() : 0;
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
                + " tool calls  "
                + reported
                + " reports";
    }
}
