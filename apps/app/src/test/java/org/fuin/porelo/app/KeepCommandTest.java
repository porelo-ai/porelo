package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@link KeepCommand}: the usage records carried off a machine and back onto one. */
class KeepCommandTest {

    private static final String RECORD = "porelo-uc-shell-1.jsonl";

    private static final String LINES = "{\"a\":1}\n{\"a\":2}\n";

    @TempDir private Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path machine;

    private Path kept;

    @BeforeEach
    void locate() {
        machine = dir.resolve("state/porelo/usage");
        kept = dir.resolve("store/usage");
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private void taskRuns() throws IOException {
        Sidecar.recordId(createdState("porelo-uc-shell-1"), "111111111111");
        runner.answering("ps", "111111111111\trunning\n");
    }

    private Path createdState(String container) throws IOException {
        return Files.createDirectories(dir.resolve("run/porelo").resolve(container));
    }

    @Test
    void aRecordWhoseLastLineIsHalfWrittenIsKeptWithoutThatLine() throws IOException {
        // A broker may be appending: half a line in the copy would stop being a prefix of the
        // record it becomes, and the next restore would refuse the task's whole history.
        write(machine.resolve(RECORD), LINES + "{\"a\":");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).as("the complete lines").isEqualTo(LINES);
        assertThat(read(machine.resolve(RECORD)))
                .as("the machine's own record")
                .isEqualTo(LINES + "{\"a\":");
    }

    @Test
    void aKeptCopyIsReadableByTheAccountAlone() throws IOException {
        // A record names the models and tools of a person's work.
        write(machine.resolve(RECORD), LINES);

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(kept)))
                .as("the directory")
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(kept.resolve(RECORD))))
                .as("the record")
                .isEqualTo("rw-------");
    }

    @Test
    void aRecordWithNoCompleteLineLeavesNothingInTheDirectory() throws IOException {
        write(machine.resolve(RECORD), "{\"a\":");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(kept.resolve(RECORD)).as("half a line is no record").doesNotExist();
    }

    @Test
    void aKeptCopyLongerThanTheMachinesIsLeftAsItIs() throws IOException {
        // A machine rebuilt and not yet restored must not shorten the history kept for it.
        write(machine.resolve(RECORD), "{\"a\":1}\n");
        write(kept.resolve(RECORD), LINES);

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).isEqualTo(LINES);
    }

    @Test
    void aKeptCopyTheMachineHasGrownPastIsReplaced() throws IOException {
        write(machine.resolve(RECORD), LINES);
        write(kept.resolve(RECORD), "{\"a\":1}\n");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).isEqualTo(LINES);
    }

    @Test
    void aKeptCopyThatDivergesIsNamedAndBothStay() throws IOException {
        // Either way a copy would lose lines nothing else holds.
        write(machine.resolve(RECORD), "{\"b\":1}\n{\"b\":2}\n");
        write(kept.resolve(RECORD), "{\"a\":1}\n");

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(read(kept.resolve(RECORD))).as("kept").isEqualTo("{\"a\":1}\n");
        assertThat(read(machine.resolve(RECORD))).as("machine").isEqualTo("{\"b\":1}\n{\"b\":2}\n");
        assertThat(err.toString()).as("named").contains(RECORD);
    }

    @Test
    void onlyTheRecordsAreCopied() throws IOException {
        // The copy goes to a project's store: nothing of a vault or a task's state may go along.
        write(machine.resolve(RECORD), LINES);
        write(dir.resolve("state/porelo/vault.kdbx"), "held");
        write(dir.resolve("state/porelo/tasks/one/task.json"), "held");
        write(machine.resolve("notes.txt"), "held");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        try (var copied = Files.walk(dir.resolve("store"))) {
            assertThat(
                            copied.filter(Files::isRegularFile)
                                    .map(Path::getFileName)
                                    .map(Path::toString))
                    .containsExactly(RECORD);
        }
    }

    @Test
    void aLinkAmongTheRecordsIsNotFollowedOffTheMachine() throws IOException {
        // Whoever can leave a link there would have the file it points at copied to the store.
        final Path secret = dir.resolve("secret");
        write(secret, "held\n");
        Files.createDirectories(machine);
        Files.createSymbolicLink(machine.resolve(RECORD), secret);

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(kept.resolve(RECORD)).as("the link's target").doesNotExist();
        assertThat(err.toString()).as("named").contains(RECORD);
    }

    @Test
    void aDirectoryThatCannotBeWrittenIsNamed() throws IOException {
        write(machine.resolve(RECORD), LINES);
        write(kept, "a file where the directory goes");

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(err.toString()).as("named").contains(kept.toString());
    }

    @Test
    void aMachineWithoutRecordsKeepsNothingAndSucceeds() {
        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(out.toString()).contains("0 records");
    }

    @Test
    void whatWasKeptIsRestoredByteForByteOnAFreshAccount() throws IOException {
        write(machine.resolve(RECORD), LINES + "{\"a\":");
        write(machine.resolve("porelo-uc-shell-1.removed-1.jsonl"), "{\"c\":1}\n");
        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();
        Files.delete(machine.resolve(RECORD));
        Files.delete(machine.resolve("porelo-uc-shell-1.removed-1.jsonl"));
        Files.delete(machine);
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machine.resolve(RECORD))).as("a live task's").isEqualTo(LINES);
        assertThat(read(machine.resolve("porelo-uc-shell-1.removed-1.jsonl")))
                .as("a removed task's")
                .isEqualTo("{\"c\":1}\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machine)))
                .as("the directory")
                .isEqualTo("rwx------");
    }

    @Test
    void aRestoreOntoALongerRecordKeepsTheLongerOne() throws IOException {
        write(machine.resolve(RECORD), LINES);
        write(kept.resolve(RECORD), "{\"a\":1}\n");
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machine.resolve(RECORD)))
                .as("no restore shortens a record")
                .isEqualTo(LINES);
    }

    @Test
    void aRestoreOverAShorterRecordTakesTheLongerOne() throws IOException {
        write(machine.resolve(RECORD), "{\"a\":1}\n");
        write(kept.resolve(RECORD), LINES);
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machine.resolve(RECORD))).isEqualTo(LINES);
    }

    @Test
    void twoRecordsThatDivergeAreRefusedAndBothStayWhileTheOthersAreRestored() throws IOException {
        write(machine.resolve(RECORD), "{\"b\":1}\n");
        write(kept.resolve(RECORD), LINES);
        write(kept.resolve("porelo-uc-other-2.jsonl"), "{\"c\":1}\n");
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(read(machine.resolve(RECORD))).as("machine").isEqualTo("{\"b\":1}\n");
        assertThat(read(kept.resolve(RECORD))).as("kept").isEqualTo(LINES);
        assertThat(read(machine.resolve("porelo-uc-other-2.jsonl")))
                .as("the record that does not diverge")
                .isEqualTo("{\"c\":1}\n");
        assertThat(err.toString()).as("named").contains(RECORD);
    }

    @Test
    void aRestoreWhileATaskRunsIsRefusedAndWritesNothing() throws IOException {
        // A broker may be appending: a record replaced under it loses what it writes next.
        taskRuns();
        write(machine.resolve(RECORD), "{\"a\":1}\n");
        write(kept.resolve(RECORD), LINES);

        assertThat(execute("keep", "--restore", dir.resolve("store").toString())).isEqualTo(69);

        assertThat(read(machine.resolve(RECORD))).isEqualTo("{\"a\":1}\n");
        assertThat(err.toString()).as("what to do").contains("running");
    }

    @Test
    void aRestoreIsRefusedWhenTheRuntimeCannotSayWhatRuns() throws IOException {
        // Not knowing is not the same as nothing running.
        runner.failing("ps", 125, "podman is broken");
        write(kept.resolve(RECORD), LINES);

        assertThat(execute("keep", "--restore", dir.resolve("store").toString())).isEqualTo(69);

        assertThat(machine.resolve(RECORD)).doesNotExist();
    }

    @Test
    void aLinkInTheKeptDirectoryIsNotReadOntoTheMachine() throws IOException {
        final Path secret = dir.resolve("secret");
        write(secret, "held\n");
        Files.createDirectories(kept);
        Files.createSymbolicLink(kept.resolve(RECORD), secret);
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(machine.resolve(RECORD)).doesNotExist();
        assertThat(err.toString()).as("named").contains(RECORD);
    }

    @Test
    void aRestoreFromADirectoryNothingWasKeptInIsNamed() {
        // A mistyped path answered with success would leave a machine without its history unseen.
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("nowhere").toString())).isEqualTo(70);

        assertThat(err.toString()).as("named").contains(dir.resolve("nowhere").toString());
    }

    @Test
    void aTemporaryFileLeftByAnInterruptedCopyIsNotTakenForARecord() throws IOException {
        write(machine.resolve(RECORD), LINES);
        write(kept.resolve(".keep-1.tmp"), "half");
        write(kept.resolve(".keep-2.jsonl"), "half");
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(machine.resolve(".keep-2.jsonl")).doesNotExist();
        assertThat(machine.resolve(".keep-1.tmp")).doesNotExist();
    }

    @Test
    void aHalfLineCountsAsNoRecord() throws IOException {
        write(machine.resolve(RECORD), "{\"a\":");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(out.toString()).contains("0 records");
    }

    @Test
    void aHalfLineInTheKeptDirectoryIsNotRestored() throws IOException {
        // The next line a broker appends would join it, and that line could no longer be read.
        write(kept.resolve(RECORD), LINES + "{\"a\":");
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machine.resolve(RECORD))).isEqualTo(LINES);
    }

    @Test
    void aLinkInPlaceOfTheMachinesDirectoryIsNotFollowed() throws IOException {
        // Whatever lies behind the link would be copied to the store.
        final Path elsewhere = dir.resolve("elsewhere");
        write(elsewhere.resolve(RECORD), LINES);
        Files.createDirectories(machine.getParent());
        Files.createSymbolicLink(machine, elsewhere);

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(kept.resolve(RECORD)).doesNotExist();
        assertThat(err.toString()).as("named").contains(machine.toString());
    }

    @Test
    void aRemovedTasksRecordReplacesTheCopyKeptUnderItsLiveName() throws IOException {
        // Left there, a restore would give the same requests twice under two names.
        write(kept.resolve(RECORD), "{\"a\":1}\n");
        write(machine.resolve("porelo-uc-shell-1.removed-7.jsonl"), LINES);

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(kept.resolve(RECORD)).as("the old name").doesNotExist();
        assertThat(read(kept.resolve("porelo-uc-shell-1.removed-7.jsonl"))).isEqualTo(LINES);
    }

    @Test
    void aNewTaskOfARemovedTasksNameIsKeptToo() throws IOException {
        // A task's name comes back: seen as a divergence, its record would never be kept again.
        write(kept.resolve(RECORD), "{\"a\":1}\n");
        write(machine.resolve("porelo-uc-shell-1.removed-7.jsonl"), LINES);
        write(machine.resolve(RECORD), "{\"new\":1}\n");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).as("the new task's").isEqualTo("{\"new\":1}\n");
        assertThat(read(kept.resolve("porelo-uc-shell-1.removed-7.jsonl")))
                .as("the removed task's")
                .isEqualTo(LINES);
    }

    @Test
    void aCopyUnderTheLiveNameThatARemovedRecordDoesNotHoldStays() throws IOException {
        // Those lines are nowhere else.
        write(kept.resolve(RECORD), "{\"other\":1}\n");
        write(machine.resolve("porelo-uc-shell-1.removed-7.jsonl"), LINES);

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).isEqualTo("{\"other\":1}\n");
    }

    @Test
    void aLongRecordIsCopiedUpToItsLastCompleteLineThoughThatLiesFarBack() throws IOException {
        // Read backward in pieces: a long half line, or a position taken within one piece, would
        // lose the record's lines.
        final String complete = "{\"a\":1}\n".repeat(30_000);
        write(machine.resolve(RECORD), complete + "x".repeat(200_000));

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(kept.resolve(RECORD))).isEqualTo(complete);
    }

    @Test
    void noTemporaryFileStaysWhateverBecameOfARecord() throws IOException {
        write(machine.resolve("same.jsonl"), LINES);
        write(kept.resolve("same.jsonl"), LINES);
        write(machine.resolve("short.jsonl"), "{\"a\":1}\n");
        write(kept.resolve("short.jsonl"), LINES);
        write(machine.resolve("differs.jsonl"), "{\"b\":1}\n");
        write(kept.resolve("differs.jsonl"), LINES);
        write(machine.resolve("half.jsonl"), "{\"a\":");

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        try (var left = Files.list(kept)) {
            assertThat(left.map(Path::getFileName).map(Path::toString))
                    .noneMatch(name -> name.startsWith(".keep-"));
        }
    }

    @Test
    void aLinkWhereACopyGoesIsNotReadOrWrittenThrough() throws IOException {
        final Path elsewhere = dir.resolve("elsewhere");
        write(elsewhere, "{\"a\":1}\n");
        write(machine.resolve(RECORD), LINES);
        Files.createDirectories(kept);
        Files.createSymbolicLink(kept.resolve(RECORD), elsewhere);

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(read(elsewhere)).isEqualTo("{\"a\":1}\n");
        assertThat(Files.isSymbolicLink(kept.resolve(RECORD))).as("left as it was").isTrue();
        assertThat(err.toString()).as("named").contains(RECORD);
    }

    private Path machineWork() {
        return dir.resolve("state/porelo/work");
    }

    private Path keptWork() {
        return dir.resolve("store/work");
    }

    private static final String WORK = "porelo-uc-shell-1.json";

    private static final String FIRST = "2026-11-01T10:00:00Z";

    private static String work(String startedAt, String end) {
        return "{\"task\":\"porelo-uc-shell-1\",\"project\":\"uc\",\"startedAt\":\""
                + startedAt
                + "\",\"end\":"
                + end
                + "}";
    }

    @Test
    void theWorkRecordsAreKeptBesideTheUsageRecords() throws IOException {
        // What a task worked on and for how long is what planning measures: it outlives the
        // machine as the tokens do.
        write(machine.resolve(RECORD), LINES);
        write(machineWork().resolve(WORK), work(FIRST, "null"));
        write(
                machineWork().resolve("porelo-uc-old-1.removed-5.json"),
                work("2026-10-01T10:00:00Z", "\"removed\""));
        write(machineWork().resolve(".lock"), "");
        write(machineWork().resolve(WORK + ".new"), "{\"half");

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(keptWork().resolve(WORK))).isEqualTo(work(FIRST, "null"));
        assertThat(read(keptWork().resolve("porelo-uc-old-1.removed-5.json")))
                .isEqualTo(work("2026-10-01T10:00:00Z", "\"removed\""));
        try (var copied = Files.list(keptWork())) {
            assertThat(copied.map(Path::getFileName).map(Path::toString))
                    .as("no lock and no staged record")
                    .containsExactlyInAnyOrder(WORK, "porelo-uc-old-1.removed-5.json");
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keptWork())))
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(keptWork().resolve(WORK))))
                .isEqualTo("rw-------");
        assertThat(out.toString()).contains("2 work records in " + keptWork());
    }

    @Test
    void aKeptWorkRecordOfTheSameTaskTakesTheMachinesNewerOne() throws IOException {
        // A work record is rewritten whole as its task goes on, never appended to.
        write(keptWork().resolve(WORK), work(FIRST, "null"));
        write(machineWork().resolve(WORK), work(FIRST, "\"done\""));

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(keptWork().resolve(WORK))).isEqualTo(work(FIRST, "\"done\""));
    }

    @Test
    void aKeptWorkRecordOfAnotherTaskOfTheSameNameIsNamedAndBothStay() throws IOException {
        // Either way a copy would lose a task's record nothing else holds.
        write(keptWork().resolve(WORK), work(FIRST, "\"done\""));
        write(machineWork().resolve(WORK), work("2026-11-02T10:00:00Z", "null"));

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(read(keptWork().resolve(WORK))).isEqualTo(work(FIRST, "\"done\""));
        assertThat(err.toString()).as("named").contains(WORK);
    }

    @Test
    void aRemovedTasksWorkRecordReplacesTheCopyKeptUnderItsLiveName() throws IOException {
        // Left there, a restore would give one task twice, and a new task of the name would
        // differ from it at every keep.
        write(keptWork().resolve(WORK), work(FIRST, "null"));
        write(
                machineWork().resolve("porelo-uc-shell-1.removed-7.json"),
                work(FIRST, "\"removed\""));
        write(machineWork().resolve(WORK), work("2026-11-02T10:00:00Z", "null"));

        assertThat(execute("keep", dir.resolve("store").toString())).as(err.toString()).isZero();

        assertThat(read(keptWork().resolve("porelo-uc-shell-1.removed-7.json")))
                .isEqualTo(work(FIRST, "\"removed\""));
        assertThat(read(keptWork().resolve(WORK)))
                .as("the new task's")
                .isEqualTo(work("2026-11-02T10:00:00Z", "null"));
    }

    @Test
    void aWorkRecordThatIsNoRecordIsNamedAndTheOthersAreKept() throws IOException {
        write(machineWork().resolve("broken.json"), "{\"half");
        write(machineWork().resolve(WORK), work(FIRST, "null"));

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(keptWork().resolve("broken.json")).doesNotExist();
        assertThat(read(keptWork().resolve(WORK))).isEqualTo(work(FIRST, "null"));
        assertThat(err.toString()).as("named").contains("broken.json");
    }

    @Test
    void theWorkRecordsAreRestoredAndTheMachinesOwnOfATaskIsLeft() throws IOException {
        // The machine's record of a task is never older than a copy taken from it.
        write(keptWork().resolve(WORK), work(FIRST, "null"));
        write(keptWork().resolve("porelo-uc-old-1.removed-5.json"), work(FIRST, "\"removed\""));
        write(machineWork().resolve(WORK), work(FIRST, "\"done\""));
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machineWork().resolve(WORK))).isEqualTo(work(FIRST, "\"done\""));
        assertThat(read(machineWork().resolve("porelo-uc-old-1.removed-5.json")))
                .isEqualTo(work(FIRST, "\"removed\""));
    }

    @Test
    void workRecordsRestoredOnAFreshAccountAreReadableByItAlone() throws IOException {
        write(keptWork().resolve(WORK), work(FIRST, "null"));
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machineWork().resolve(WORK))).isEqualTo(work(FIRST, "null"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(machineWork())))
                .isEqualTo("rwx------");
    }

    @Test
    void aStoreKeptBeforeTheWorkRecordsIsStillRestored() throws IOException {
        write(kept.resolve(RECORD), LINES);
        runner.answering("ps", "");

        assertThat(execute("keep", "--restore", dir.resolve("store").toString()))
                .as(err.toString())
                .isZero();

        assertThat(read(machine.resolve(RECORD))).isEqualTo(LINES);
    }

    @Test
    void aLinkAmongTheWorkRecordsIsNotFollowedOffTheMachine() throws IOException {
        final Path secret = dir.resolve("secret.json");
        write(secret, work(FIRST, "null"));
        Files.createDirectories(machineWork());
        Files.createSymbolicLink(machineWork().resolve(WORK), secret);

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(keptWork().resolve(WORK)).doesNotExist();
        assertThat(err.toString()).as("named").contains(WORK);
    }

    @Test
    void aRecordThatCannotBeCopiedDoesNotStopTheOthers() throws IOException {
        // What can be saved is saved: one bad record must not cost a machine's history.
        Files.createDirectories(machine);
        Files.createSymbolicLink(machine.resolve("a-link.jsonl"), dir.resolve("nowhere"));
        write(machine.resolve(RECORD), LINES);

        assertThat(execute("keep", dir.resolve("store").toString())).isEqualTo(70);

        assertThat(read(kept.resolve(RECORD))).isEqualTo(LINES);
    }
}
