package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Answers what a task reported back, a removed task's too, oldest first: the same answer as the
 * daemon's {@code Reports}, from the same code.
 *
 * <p>Porelo keeps nothing about who read what: a reader keeps the last number it took and passes it
 * as {@code --after}.
 */
@Command(
        name = "reports",
        mixinStandardHelpOptions = true,
        description =
                "What a task reported back, a removed task's too, oldest first, at most 1 MiB:"
                        + " each report's number, time, kind and text.")
public class TaskReportsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "<task>",
            description = "The task's container name, or its own name.")
    private String task = "";

    @Option(
            names = "--after",
            paramLabel = "<n>",
            description =
                    "Answers only the reports numbered after this one; 0, the default, for all.")
    private long after;

    @Option(
            names = "--json",
            description = "Answers a program: reports, last and more, as the daemon's Reports.")
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
        // By the container's name or the task's own, as every task command takes it.
        task = TaskTarget.spelled(context, task);
        if (!TaskResult.named(task)) {
            err.println("porelo: '" + task + "' is no task's name");
            err.flush();
            return 64;
        }
        if (after < 0) {
            err.println("porelo: --after takes the last number read, 0 or more, not " + after);
            err.flush();
            return 64;
        }
        final TaskReports.Page page;
        try {
            page = new TaskReports(context.paths()).page(task, after);
        } catch (IOException ex) {
            err.println(
                    "porelo: the reports of " + task + " could not be read: " + ex.getMessage());
            err.flush();
            return 74;
        }
        if (json) {
            out.println(Json.write(page.asMap()));
        } else {
            for (final TaskReports.Report report : page.reports()) {
                // The task's words escaped as every command escapes outside text: a bidi control,
                // a zero-width character or a separator would otherwise forge or reorder a line.
                out.println(
                        report.seq()
                                + "  "
                                + report.at()
                                + "  "
                                + TerminalText.line(report.kind()));
                // Each line indented, a carriage return never written: it would overwrite the head.
                for (final String line : report.text().split("\r\n|\r|\n", -1)) {
                    out.println("  " + TerminalText.line(line));
                }
            }
            if (page.more()) {
                out.println(
                        "more follow: porelo task reports "
                                + task
                                + " --after "
                                + page.reports().getLast().seq());
            }
        }
        out.flush();
        return 0;
    }
}
