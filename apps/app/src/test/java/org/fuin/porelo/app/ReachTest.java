package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.shield.DnsPolicy;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Reach}: a task's own answer must say what its run was widened or narrowed to, or
 * an operator reads the machine's bound for a task that has more.
 */
class ReachTest {

    private static final String TASK = "porelo-uc-shell-1";

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A running guarded task with the files a live widening touches. */
    private Path task(PoreloContext context, String clearance) throws IOException {
        runner.answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\n");
        final Path state = context.paths().tasks().containerState(TASK);
        Files.createDirectories(state);
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        Files.writeString(
                state.resolve(DnsPolicy.SERVERS_FILE),
                "server=/declared.test/192.0.2.53\n",
                StandardCharsets.UTF_8);
        org.fuin.porelo.wire.HelperPid.record(state.resolve("dnsmasq.pid"));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.AGENT,
                        null,
                        "porelo/work",
                        "2026-09-08T06:00:00Z",
                        clearance)
                .writeTo(state);
        return state;
    }

    private static Reach.Row row(List<Reach.Row> rows, String subject) {
        return rows.stream()
                .filter(each -> each.subject().equals(subject))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + subject + " in " + rows));
    }

    private static List<Reach.Row> rowsOf(PoreloContext context) {
        return new Reach(context).of(TASK).orElseThrow();
    }

    @Test
    void aWidenedTaskAnswersTheNameItWasGrantedWhereTheMachineAnswersNone(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        task(context, "prompt");
        assertThat(
                        new RunningEgress(context)
                                .widen(
                                        TASK,
                                        List.of("docs.example.test"),
                                        RunningEgress.Scope.RUN,
                                        false)
                                .outcome())
                .as("the fixture widens the run")
                .isEqualTo(RunningEgress.Outcome.WIDENED);

        final List<Reach.Row> rows = rowsOf(context);

        assertThat(row(rows, "A declared host").task())
                .as("the run reaches the granted name on the declared host's ports")
                .contains("docs.example.test")
                .contains("80 and 443");
        assertThat(row(rows, "A name the project did not declare").task())
                .as("a granted name resolves although the project never declared it")
                .contains("docs.example.test");
        assertThat(row(new Reach(context).machine(), "A declared host").task())
                .as("the machine's answer has no task in it")
                .isNull();
    }

    @Test
    void aNarrowedNameIsNoLongerAnsweredForTheTask(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        task(context, "prompt");
        final RunningEgress egress = new RunningEgress(context);
        egress.widen(
                TASK,
                List.of("docs.example.test", "cdn.example.test"),
                RunningEgress.Scope.RUN,
                false);
        assertThat(
                        egress.narrow(
                                        TASK,
                                        List.of("docs.example.test"),
                                        RunningEgress.Scope.RUN,
                                        false)
                                .outcome())
                .as("the fixture narrows the run")
                .isEqualTo(RunningEgress.Outcome.NARROWED);

        assertThat(row(rowsOf(context), "A declared host").task())
                .as("the name taken back is gone, the one still granted stays")
                .contains("cdn.example.test")
                .doesNotContain("docs.example.test");
    }

    @Test
    void aTaskThatWasNeitherWidenedNorNarrowedHoldsTheMachinesBounds(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        task(context, "prompt");

        assertThat(rowsOf(context))
                .as("nothing of this task differs from the machine")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.task()).as(row.subject()).isNull());
    }

    @Test
    void aTaskWhoseClearanceLetsEverythingThroughSaysSoWhereAPromptWouldBe(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        task(context, "allow");

        assertThat(row(rowsOf(context), "A reach for something undeclared").task())
                .as("an undeclared reach is let through and nobody is asked")
                .contains("allow");
    }

    @Test
    void aTaskHandedTheHostAuthorityAnswersItOnTheTrustRow(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path state = task(context, "prompt");
        Files.writeString(state.resolve(TaskCertificates.FILE), "pem");

        assertThat(row(rowsOf(context), "Additional TLS trust").task())
                .as("this task trusts one authority more than the system store")
                .contains(TaskCertificates.FILE);
    }

    @Test
    void aStoppedTaskWhoseRuntimeStateARebootEmptiedAnswersFromItsSavedRecord(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", TASK + "\tExited (0) 2 minutes ago\t1788500000\t1788500100\n");
        final Path record = context.paths().tasks().taskRecord(TASK);
        Files.createDirectories(record);
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.AGENT,
                        null,
                        "porelo/work",
                        "2026-09-08T06:00:00Z",
                        "prompt")
                .writeTo(record);
        org.fuin.porelo.wire.GrantedNames.add(record, "docs.example.test");
        Files.writeString(record.resolve(TaskCertificates.FILE), "pem");
        assertThat(context.paths().tasks().containerState(TASK))
                .as("the fixture's runtime directory is gone, as after a reboot")
                .doesNotExist();

        final List<Reach.Row> rows = rowsOf(context);

        assertThat(row(rows, "A declared host").task())
                .as("the saved grant still holds for the task's next start")
                .contains("docs.example.test");
        assertThat(row(rows, "Additional TLS trust").task())
                .as("the saved authority is copied back in at the next start")
                .contains(TaskCertificates.FILE);
    }

    @Test
    void anUnknownTaskIsNoAnswerRatherThanTheMachines(@TempDir Path dir) {
        assertThat(new Reach(context(dir)).of("porelo-uc-shell-9"))
                .as("a task that does not exist must not be answered as if it held nothing")
                .isEmpty();
    }
}
