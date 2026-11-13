package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whether a recorded process id still names the process it was written for. */
class HelperPidTest {

    @Test
    void thisProcessRecognisesItself(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        HelperPid.record(file);

        assertThat(HelperPid.verified(file))
                .get()
                .extracting(ProcessHandle::pid)
                .isEqualTo(ProcessHandle.current().pid());
    }

    @Test
    void anIdThatNowBelongsToSomethingElseIsNotSignalled(@TempDir Path dir) throws Exception {

        // The defect this exists for: process ids are reused. A helper that exited leaves its file
        // behind, and by the time something cleans up, that number can belong to an editor, an ssh
        // agent or another task's helper - all owned by the same user, so the operating system
        // permits the signal. Recording the start time is what tells them apart.
        final Path file = dir.resolve("helper.pid");
        Files.writeString(file, ProcessHandle.current().pid() + " 1", StandardCharsets.UTF_8);

        assertThat(HelperPid.verified(file))
                .as("same id, a start time from 1970: not the same process")
                .isEmpty();
    }

    @Test
    void aRecordWithoutAStartTimeIsNeverSignalled(@TempDir Path dir) throws Exception {

        // What an older build wrote: a bare number. There is nothing to compare, and the safe
        // answer to "is this still the helper" is no - an orphaned helper is visible and can be
        // stopped by name, whereas a process killed by mistake is simply gone.
        final Path file = dir.resolve("helper.pid");
        Files.writeString(
                file, String.valueOf(ProcessHandle.current().pid()), StandardCharsets.UTF_8);

        assertThat(HelperPid.verified(file)).isEmpty();
    }

    @Test
    void rubbishIsNotSignalledEither(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        Files.writeString(file, "not a pid at all", StandardCharsets.UTF_8);
        assertThat(HelperPid.verified(file)).isEmpty();

        assertThat(HelperPid.verified(dir.resolve("absent.pid"))).isEmpty();
    }

    @Test
    void aHelperIsAwaitedUntilItsRecordNamesItAndNoLonger(@TempDir Path dir) throws Exception {

        // A start that went on before its helper recorded itself answered success for a task
        // whose gate never came up, and left nothing a clean-up could find.
        final Path file = dir.resolve("helper.pid");
        final Thread late =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(300);
                                        HelperPid.record(file);
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });

        assertThat(HelperPid.await(file, HelperPid.deadline(java.time.Duration.ofSeconds(20))))
                .as("a record written while it waits is the helper")
                .get()
                .extracting(ProcessHandle::pid)
                .isEqualTo(ProcessHandle.current().pid());
        late.join();
    }

    @Test
    void aHelperThatNeverRecordsItselfIsNotUpAtItsDeadline(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        Files.writeString(
                file, String.valueOf(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        final long before = System.nanoTime();

        assertThat(HelperPid.await(file, HelperPid.deadline(java.time.Duration.ofMillis(400))))
                .as("a bare number names no helper that came up")
                .isEmpty();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - before))
                .as("it waited out its deadline, and not much more")
                .isBetween(java.time.Duration.ofMillis(350), java.time.Duration.ofSeconds(10));
    }

    @Test
    void aHelperThatIgnoresTheSignalIsKilledOnceItsGracePassed() throws Exception {

        // Asked once and left, a helper that ignored SIGTERM ran on after the start that gave up
        // on it answered, and nothing named it any more.
        final Process stubborn =
                new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 60 & wait; sleep 60")
                        .start();
        try {
            // Its child is up once the shell waits: the signal is ignored by both.
            Thread.sleep(300);
            final ProcessHandle child = stubborn.toHandle().children().findFirst().orElseThrow();

            assertThat(HelperPid.end(stubborn.toHandle(), java.time.Duration.ofMillis(300)))
                    .as("ended, if only by a kill")
                    .isTrue();
            assertThat(stubborn.isAlive()).as("the helper").isFalse();
            assertThat(child.isAlive()).as("and what it started").isFalse();
        } finally {
            stubborn.destroyForcibly();
        }
    }

    @Test
    void aHelperWhoseIdWasTakenLeadsToNoneOfTheNewHoldersChildren() throws Exception {

        // An exited helper's id, taken by another process: asked for the stale handle's
        // descendants, the runtime lists the new holder's children, started after the helper and
        // so passing its start-time filter, and signalling them ended what nobody asked to touch.
        final Process replacement = new ProcessBuilder("/bin/sh", "-c", "sleep 60 & wait").start();
        try {
            Thread.sleep(300);
            final ProcessHandle child = replacement.toHandle().children().findFirst().orElseThrow();
            final ProcessHandle stale = new Stale(replacement.toHandle());

            assertThat(HelperPid.descendants(stale)).as("nothing is the stale helper's").isEmpty();
            HelperPid.stop(stale);
            assertThat(HelperPid.end(stale, java.time.Duration.ofMillis(300)))
                    .as("a helper that is gone is ended")
                    .isTrue();
            assertThat(child.isAlive()).as("and the new holder's child runs on").isTrue();
            assertThat(replacement.isAlive()).as("as does the new holder").isTrue();
        } finally {
            replacement.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            replacement.destroyForcibly();
        }
    }

    @Test
    void aChildTheHelperStartsWhileItIsAskedToGoIsEndedToo(@TempDir Path dir) throws Exception {

        // One census taken before the signal: a helper whose SIGTERM handler started a child and
        // waited for it was killed, end answered that everything was gone, and the child ran on.
        final Path late = dir.resolve("late.pid");
        final Process helper =
                new ProcessBuilder(
                                "/bin/sh",
                                "-c",
                                "trap 'sleep 60 & echo $! > "
                                        + late
                                        + "; wait' TERM;"
                                        + " sleep 60 & wait")
                        .start();
        try {
            Thread.sleep(300);

            assertThat(HelperPid.end(helper.toHandle(), java.time.Duration.ofMillis(500)))
                    .as("ended, with what it started on the way out")
                    .isTrue();
            assertThat(helper.isAlive()).as("the helper").isFalse();
            final long pid = Long.parseLong(Files.readString(late, StandardCharsets.UTF_8).strip());
            assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive))
                    .as("the child its handler started")
                    .isEmpty();
        } finally {
            if (Files.exists(late)) {
                final String pid = Files.readString(late, StandardCharsets.UTF_8).strip();
                if (!pid.isEmpty()) {
                    ProcessHandle.of(Long.parseLong(pid))
                            .filter(handle -> handle.info().command().orElse("").endsWith("sleep"))
                            .ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            helper.destroyForcibly();
        }
    }

    /**
     * A handle whose process exited and whose id another process now holds: alive no longer, and
     * signalling it reaches nothing, but the runtime lists the new holder's children as its own.
     */
    private record Stale(ProcessHandle holder) implements ProcessHandle {

        @Override
        public long pid() {
            return holder.pid();
        }

        @Override
        public java.util.Optional<ProcessHandle> parent() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.stream.Stream<ProcessHandle> children() {
            return holder.children();
        }

        @Override
        public java.util.stream.Stream<ProcessHandle> descendants() {
            return holder.descendants();
        }

        @Override
        public Info info() {
            return holder.info();
        }

        @Override
        public java.util.concurrent.CompletableFuture<ProcessHandle> onExit() {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public int compareTo(final ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }

    @Test
    void theRecordIsOwnerOnly(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        HelperPid.record(file);

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
