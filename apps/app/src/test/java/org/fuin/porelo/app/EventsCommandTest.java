package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.clearance.ClearanceJournal;
import org.fuin.porelo.clearance.Decision;
import org.fuin.porelo.clearance.Verdict;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@code porelo events} and the watcher's decisions in the account's event log. */
class EventsCommandTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private int execute(final PoreloContext context, final String... arguments) {
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        return cli.execute(arguments);
    }

    @Test
    void aDecisionOfTheWatcherGoesToTheEventLogBesideTheTasksJournal() throws Exception {
        final PoreloContext context = context();
        final ClearanceJournal journal =
                ShieldWatchCommand.eventLogged(
                        new ClearanceJournal(dir.resolve("clearance.jsonl")), context);

        journal.record(
                new Decision(
                        java.time.Instant.now(),
                        "uc",
                        "fix",
                        "tcp/example.org/443",
                        "example.org",
                        443,
                        "tcp",
                        "example.org:443",
                        Verdict.ALLOW,
                        Decision.PROMPT));

        assertThat(dir.resolve("clearance.jsonl")).exists();
        assertThat(Files.readString(context.paths().eventLog()))
                .contains("\"kind\":\"clearance\"")
                .contains("allow tcp example.org:443 (prompt)");
    }

    @Test
    void eventsAreShownAndAChangedLineMakesVerifyFail() throws Exception {
        final PoreloContext context = context();
        EventLog.record(context, EventLog.APPROVED, "uc", "fix", "onto main");
        EventLog.record(context, EventLog.REJECTED, "uc", "other", "discarded");
        final String sent =
                runner.invocations().stream()
                        .filter(command -> command.arguments().contains("logger"))
                        .map(command -> String.valueOf(command.input()))
                        .reduce("", String::concat);
        final StringBuilder journal = new StringBuilder();
        for (final String line : sent.lines().toList()) {
            if (line.startsWith("PORELO_EVENT_HASH=")) {
                journal.append("{\"PORELO_EVENT_HASH\":\"")
                        .append(line.substring("PORELO_EVENT_HASH=".length()))
                        .append("\",\"__REALTIME_TIMESTAMP\":\"")
                        .append(java.time.Instant.now().toEpochMilli() * 1000L)
                        .append("\"}\n");
            }
        }
        runner.answering("journalctl", journal.toString());

        assertThat(execute(context, "events")).isZero();
        assertThat(out.toString()).contains("approved  uc  fix  onto main").contains("rejected");

        out.getBuffer().setLength(0);
        assertThat(execute(context, "events", "--verify")).as(out.toString()).isZero();
        assertThat(out.toString()).contains("holds");

        final Path log = context.paths().eventLog();
        Files.writeString(
                log,
                Files.readString(log).replace("onto main", "onto release"),
                StandardCharsets.UTF_8);
        out.getBuffer().setLength(0);
        assertThat(execute(context, "events", "--verify")).isEqualTo(1);
        assertThat(out.toString()).contains("CHANGED   the chain breaks at line 1");
    }
}
