package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Tests for {@link ProcessCommandRunner}. */
class ProcessCommandRunnerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"exit", "timeout", "interrupt"})
    @org.junit.jupiter.api.Timeout(15)
    void waitsForTheInputWriterOnEveryReturnPath(String path) throws Exception {
        final var writing = new java.util.concurrent.CountDownLatch(1);
        final var waited = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var returned = new java.util.concurrent.CountDownLatch(1);
        final var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        final Process child =
                new Process() {
                    public java.io.OutputStream getOutputStream() {
                        return new java.io.OutputStream() {
                            public void write(int value) {
                                throw new AssertionError("binary write expected");
                            }

                            @Override
                            public void write(byte[] value) throws java.io.IOException {
                                writing.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException ex) {
                                    throw new java.io.IOException(ex);
                                }
                            }
                        };
                    }

                    public java.io.InputStream getInputStream() {
                        return java.io.InputStream.nullInputStream();
                    }

                    public java.io.InputStream getErrorStream() {
                        return java.io.InputStream.nullInputStream();
                    }

                    public int waitFor() {
                        return 0;
                    }

                    @Override
                    public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
                            throws InterruptedException {
                        writing.await();
                        waited.countDown();
                        if (path.equals("interrupt")) {
                            throw new InterruptedException("interrupted child wait");
                        }
                        return !path.equals("timeout");
                    }

                    public int exitValue() {
                        return 0;
                    }

                    public void destroy() {}

                    @Override
                    public Process destroyForcibly() {
                        return this;
                    }

                    @Override
                    public java.util.stream.Stream<ProcessHandle> descendants() {
                        return java.util.stream.Stream.empty();
                    }
                };
        final var runner =
                new ProcessCommandRunner(Duration.ofMillis(1)) {
                    @Override
                    protected Process start(ProcessBuilder builder) {
                        return child;
                    }
                };
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread caller =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        runner.run(
                                                Command.of("unused").withBinaryInput(new byte[32]));
                                    } catch (Throwable ex) {
                                        failure.set(ex);
                                    } finally {
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                        returned.countDown();
                                    }
                                });
        try {
            assertThat(waited.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the child has ended while its writer still owns the input")
                    .isTrue();
            assertThat(
                            returned.await(
                                    path.equals("exit") ? 2500 : 200,
                                    java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("%s cannot return while the caller's input is still being written", path)
                    .isFalse();
        } finally {
            release.countDown();
            caller.join();
        }
        if (path.equals("exit")) {
            assertThat(failure.get()).as("normal exit still succeeds").isNull();
        } else {
            assertThat(failure.get())
                    .as("timeout and interruption are reported")
                    .isInstanceOf(CommandException.class);
        }
        assertThat(interrupted.get())
                .as("interruption survives the uninterruptible cleanup")
                .isEqualTo(path.equals("interrupt"));
    }

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));

    @Test
    void aChosenEnvironmentIsAllTheProgramIsGiven() {
        // A root helper refuses any variable it does not know, so nothing of this process may pass.
        final CommandResult shown =
                runner.run(
                        Command.of("/usr/bin/env")
                                .withChosenEnvironment(
                                        java.util.Map.of("PATH", "/usr/bin:/bin", "LC_ALL", "C")));

        assertThat(shown.standardOutput().lines().sorted().toList())
                .containsExactly("LC_ALL=C", "PATH=/usr/bin:/bin");
    }

    @Test
    void capturesStandardOutput() {

        final CommandResult result = runner.run(Command.of("echo", "hello"));

        assertThat(result.successful()).isTrue();
        assertThat(result.trimmedOutput()).isEqualTo("hello");
    }

    @Test
    void capturesStandardErrorAndTheExitCode() {

        final CommandResult result = runner.run(Command.of("sh", "-c", "echo boom >&2; exit 3"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.standardError()).contains("boom");
        assertThat(result.successful()).isFalse();
    }

    @Test
    void writesBinaryInputWithoutEncodingItAsText() {
        final Command command =
                Command.of("od", "-An", "-t", "u1")
                        .withBinaryInput(new byte[] {0, 10, 13, (byte) 255});
        final CommandResult result = runner.run(command);
        assertThat(result.standardOutput().trim().split("\\s+"))
                .as("stdin keeps every byte, including NUL and invalid UTF-8")
                .containsExactly("0", "10", "13", "255");
        assertThat(command.toString()).as("diagnostics never expose input").doesNotContain("255");
    }

    @Test
    void writesStandardInput() {

        final CommandResult result = runner.run(Command.of("cat").withInput("piped"));

        assertThat(result.trimmedOutput()).isEqualTo("piped");
    }

    @Test
    void doesNotDeadlockOnOutputLargerThanAPipeBuffer() {

        // A pipe buffer is 64 KiB. Without a reader draining it, this hangs instead of failing,
        // which is why the drain exists at all.
        final CommandResult result =
                runner.run(Command.of("sh", "-c", "yes abcdefgh | head -c 500000"));

        assertThat(result.standardOutput()).hasSize(500000);
    }

    @Test
    void reportsTheProgramsOwnErrorOutputWhenFailing() {

        assertThatThrownBy(
                        () ->
                                runner.runOrFail(
                                        Command.of(
                                                "sh", "-c", "echo 'no such image' >&2; exit 125")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("exit code 125")
                .hasMessageContaining("no such image");
    }

    @Test
    void reportsAProgramThatCannotBeStarted() {

        assertThatThrownBy(() -> runner.run(Command.of("porelo-no-such-program")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Cannot run");
    }

    @Test
    void killsAProgramThatOutlivesItsTimeout() {

        final CommandRunner impatient = new ProcessCommandRunner(Duration.ofMillis(200));

        assertThatThrownBy(() -> impatient.run(Command.of("sleep", "30")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Timed out");
    }

    @Test
    void aCommandsOwnShorterLimitEndsItAndSaysItWasTheLimit() {

        final java.time.Instant started = java.time.Instant.now();

        assertThatThrownBy(
                        () ->
                                runner.run(
                                        Command.of("sleep", "30")
                                                .withTimeout(Duration.ofMillis(300))))
                .isInstanceOfSatisfying(
                        CommandException.class,
                        ex ->
                                assertThat(ex.timedOut())
                                        .as(
                                                "a caller tells the limit from a failure without the text")
                                        .isTrue())
                .hasMessageContaining("Timed out after");
        assertThat(Duration.between(started, java.time.Instant.now()))
                .as("ended within the command's own limit, not the runner's twenty seconds")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void theRunnersOwnLimitStillHoldsForACommandWithALongerOne() {

        final CommandRunner impatient = new ProcessCommandRunner(Duration.ofMillis(200));

        assertThatThrownBy(
                        () ->
                                impatient.run(
                                        Command.of("sleep", "30").withTimeout(Duration.ofHours(1))))
                .isInstanceOfSatisfying(
                        CommandException.class,
                        ex ->
                                assertThat(ex.limit())
                                        .as("the limit that ended it, which a message names")
                                        .isEqualTo(Duration.ofMillis(200)));
    }

    @Test
    void aProgramEndedAtItsLimitIsGoneAfterwards(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {

        final java.nio.file.Path pid = dir.resolve("pid");

        assertThatThrownBy(
                        () ->
                                runner.run(
                                        Command.of(
                                                        "sh",
                                                        "-c",
                                                        "echo $$ > \"$1\"; exec sleep 30",
                                                        "sh",
                                                        pid.toString())
                                                .withTimeout(Duration.ofMillis(500))))
                .isInstanceOfSatisfying(
                        CommandException.class, ex -> assertThat(ex.timedOut()).isTrue());
        final long ended = Long.parseLong(java.nio.file.Files.readString(pid).strip());
        // Said to have timed out is not enough: a push left running would still reach the forge.
        final java.time.Instant until = java.time.Instant.now().plusSeconds(5);
        while (ProcessHandle.of(ended).map(ProcessHandle::isAlive).orElse(false)
                && java.time.Instant.now().isBefore(until)) {
            Thread.onSpinWait();
        }
        assertThat(ProcessHandle.of(ended).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void aFailureIsNotATimeout() {

        assertThatThrownBy(() -> runner.runOrFail(Command.of("sh", "-c", "exit 3")))
                .isInstanceOfSatisfying(
                        CommandException.class, ex -> assertThat(ex.timedOut()).isFalse());
    }

    @org.junit.jupiter.api.Test
    void aChildThatLeavesAGrandchildHoldingItsOutputDoesNotHoldTheCallerToo() {

        // Only the child was ended, and the output was then waited for without a limit: a
        // grandchild that kept it open - a git helper under 'git fetch' against an upstream that
        // feeds slowly - held the caller for ever.
        final java.time.Instant started = java.time.Instant.now();
        new ProcessCommandRunner(java.time.Duration.ofSeconds(1))
                .run(Command.of("sh", "-c", "sleep 8 & exit 0"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new ProcessCommandRunner(java.time.Duration.ofSeconds(1))
                                        .run(Command.of("sh", "-c", "sleep 8 & sleep 8")))
                .isInstanceOf(CommandException.class);

        org.assertj.core.api.Assertions.assertThat(
                        java.time.Duration.between(started, java.time.Instant.now()))
                .isLessThan(java.time.Duration.ofSeconds(6));
    }

    @org.junit.jupiter.api.Test
    void aChildThatDoesNotReadItsInputStillSucceeds() {

        // The input was written before the limit ran, and a child that exited without reading it
        // made a successful command fail with "Stream closed".
        org.assertj.core.api.Assertions.assertThat(
                        new ProcessCommandRunner(java.time.Duration.ofSeconds(5))
                                .run(Command.of("true").withInput("x".repeat(1024 * 1024)))
                                .exitCode())
                .isZero();
    }

    @org.junit.jupiter.api.Test
    void outputBeyondItsLimitIsKeptAsItsEndNotHeldWhole() {

        // Collected without a limit, what a hostile server made git print could run the daemon out
        // of memory. END stands on a line of its own: the line the cut falls in is not kept.
        final CommandResult result =
                new ProcessCommandRunner(java.time.Duration.ofSeconds(30))
                        .run(
                                Command.of(
                                        "sh",
                                        "-c",
                                        "head -c 40000000 /dev/zero | tr '\\0' 'x'; echo; echo END"));

        org.assertj.core.api.Assertions.assertThat(result.standardOutput().length())
                .isLessThan(20_000_000);
        org.assertj.core.api.Assertions.assertThat(result.standardOutput())
                .contains("cut")
                .endsWith("END\n");
    }

    @Test
    void aTailCutMidwayThroughALineKeepsNoneOfThatLine() {
        // Cut midway through a forge's "remote:" line, the tail began with what the forge said and
        // without the prefix that marks it as the forge's.
        final CommandResult result =
                new ProcessCommandRunner(Duration.ofSeconds(30))
                        .run(
                                Command.of(
                                        "sh",
                                        "-c",
                                        "{ printf 'remote: '; head -c 20000000 /dev/zero | tr '\\0'"
                                                + " 'x'; echo ' lent-token'; echo 'fatal: the end';"
                                                + " } >&2"));

        assertThat(result.standardError())
                .as("the cut is said")
                .contains("cut")
                .doesNotContain("lent-token")
                .doesNotContain("x")
                .endsWith("fatal: the end\n");
    }

    @Test
    void aLineOfStandardErrorArrivesWhileTheProgramStillRuns(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) {
        final java.nio.file.Path seen = dir.resolve("seen");
        final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        // It ends only once the line was taken: kept until the end, the limit ends it instead.
        final CommandResult result =
                new ProcessCommandRunner(Duration.ofSeconds(20))
                        .run(
                                Command.of(
                                        "sh",
                                        "-c",
                                        "echo first >&2; printf 'on stdout\\n';"
                                                + " while [ ! -e \"$0\" ]; do sleep 0.05; done;"
                                                + " printf 'last without an end' >&2",
                                        seen.toString()),
                                line -> {
                                    lines.add(line);
                                    try {
                                        java.nio.file.Files.writeString(seen, "");
                                    } catch (java.io.IOException ex) {
                                        throw new java.io.UncheckedIOException(ex);
                                    }
                                });

        assertThat(lines).containsExactly("first", "last without an end");
        assertThat(result.standardError()).isEqualTo("first\nlast without an end");
        assertThat(result.standardOutput()).isEqualTo("on stdout\n");
    }

    @Test
    void aConsumerThatHoldsItsLineHoldsNeitherTheProgramNorWhatItSaid() throws Exception {
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        final CommandResult result =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(15),
                        () ->
                                new ProcessCommandRunner(Duration.ofSeconds(60))
                                        .run(
                                                Command.of(
                                                        "sh", "-c", "seq 1 20000 >&2; echo done"),
                                                line -> {
                                                    lines.add(line);
                                                    try {
                                                        release.await();
                                                    } catch (InterruptedException ex) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                }));

        assertThat(result.standardOutput()).isEqualTo("done\n");
        assertThat(result.standardError().lines().count())
                .as("all of it, though nobody took more than its first line")
                .isEqualTo(20000);
        release.countDown();
        Thread.sleep(300);
        assertThat(lines).as("nothing is handed over once the run has ended").containsExactly("1");
    }

    @Test
    void aConsumerThatFailsCostsTheProgramNothing() {
        final CommandResult result =
                new ProcessCommandRunner(Duration.ofSeconds(20))
                        .run(
                                Command.of("sh", "-c", "echo one >&2; echo two >&2; echo done"),
                                line -> {
                                    throw new IllegalStateException("no terminal");
                                });

        assertThat(result.exitCode()).isZero();
        assertThat(result.standardError()).isEqualTo("one\ntwo\n");
        assertThat(result.standardOutput()).isEqualTo("done\n");
    }

    @Test
    void aLineTooLongIsNotShownInPieces() {
        final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        new ProcessCommandRunner(Duration.ofSeconds(20))
                .run(
                        Command.of(
                                "sh",
                                "-c",
                                "head -c 100000 /dev/zero | tr '\\0' x >&2; echo >&2; echo after >&2"),
                        lines::add);

        assertThat(lines)
                .as("a secret cut in two would pass any redaction line by line")
                .containsExactly("[a line too long to show]", "after");
    }

    @Test
    void aRunnerOfNoLinesOfItsOwnHandsThemOverAtTheEnd() {
        final CommandRunner plain = command -> new CommandResult(command, 0, "", "one\r\ntwo\n");
        final java.util.List<String> lines = new java.util.ArrayList<>();

        plain.run(Command.of("x"), lines::add);

        assertThat(lines).containsExactly("one", "two");
    }
}
