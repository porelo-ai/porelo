package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Copies the tasks' usage and work records into a directory it is given, and puts them back on a
 * new machine, so the history a project's velocity is computed from outlives the machine it was
 * written on. Taking the copy elsewhere, and when, is for whoever calls it.
 */
@Command(
        name = "keep",
        mixinStandardHelpOptions = true,
        description =
                "Copies the usage and work records into a directory, or puts them back from one.")
public class KeepCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The directory beneath the one given that holds the copies. */
    private static final String USAGE = "usage";

    /** The directory beneath the one given that holds the copies of the work records. */
    private static final String WORK = "work";

    /** The work records' lock, which porelod and a command line hold while they write one. */
    private static final String WORK_LOCK = ".lock";

    /** The name a work record is given when its task is removed. */
    private static final Pattern REMOVED_WORK = Pattern.compile("\\.removed-\\d+\\.json$");

    /** Only a record's complete lines are copied, so its end is read back this much at a time. */
    private static final int BACKWARD = 64 * 1024;

    /** The name a record is given when its task is removed; a new task may take the old one. */
    private static final Pattern REMOVED = Pattern.compile("\\.removed-\\d+\\.jsonl$");

    /** What became of one record. */
    private enum Outcome {
        NOTHING_COMPLETE,
        NOT_COPIED,
        COPIED,
        UNCHANGED,
        LONGER_LEFT,
        NEWER_LEFT,
        DIVERGED
    }

    @Parameters(index = "0", paramLabel = "<dir>", description = "Where the copies are.")
    private Path directory = Path.of("");

    @Option(names = "--restore", description = "Puts the records back from the directory.")
    private boolean restore;

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
        final Path machine = context.paths().tasks().usageRecords();
        final Path kept = directory.resolve(USAGE);
        final Path machineWork = context.paths().tasks().workRecords();
        final Path keptWork = directory.resolve(WORK);
        final int code;
        if (restore) {
            final String refused = whyATaskForbidsIt();
            if (refused != null) {
                err.println("porelo: " + refused);
                err.flush();
                return 69;
            }
            final boolean usage = Files.isDirectory(kept, LinkOption.NOFOLLOW_LINKS);
            final boolean work = Files.isDirectory(keptWork, LinkOption.NOFOLLOW_LINKS);
            if (!usage && !work) {
                err.println("porelo: no usage or work records were kept in " + directory);
                err.flush();
                return 70;
            }
            // A directory kept before the work records were holds none of them.
            final int usageCode = usage ? restore(kept, machine) : 0;
            final int workCode = work ? restoreWork(keptWork, machineWork) : 0;
            code = Math.max(usageCode, workCode);
        } else {
            code = Math.max(keep(machine, kept), keepWork(machineWork, keptWork));
        }
        out.flush();
        err.flush();
        return code;
    }

    /**
     * Says what forbids writing records now.
     *
     * @return The reason, or {@code null} when no task runs.
     */
    private @Nullable String whyATaskForbidsIt() {
        final List<String> running;
        try {
            running = new TaskPanic(context).candidates();
        } catch (RuntimeException ex) {
            // Not knowing is not the same as nothing running, and a broker may be appending.
            return "cannot tell whether a task is running: " + ex.getMessage();
        }
        return running.isEmpty()
                ? null
                : "a task is running, and its broker may be appending to its record: "
                        + String.join(", ", running)
                        + "; stop it first";
    }

    /**
     * Copies a machine's work records out, under the lock their writers hold: a removed task's
     * first, so the copy kept under its live name can be retired.
     */
    private int keepWork(Path machine, Path kept) {
        final PrintWriter err = spec.commandLine().getErr();
        if (!prepared(kept)) {
            return 70;
        }
        final Tally tally = new Tally();
        try {
            lockedIfThere(
                    machine,
                    () -> {
                        // Listed under the lock: a removal renames a record meanwhile.
                        final List<Path> records = workRecords(machine);
                        final List<Path> removed =
                                records.stream().filter(KeepCommand::isRemovedWork).toList();
                        final List<Path> live =
                                records.stream().filter(path -> !isRemovedWork(path)).toList();
                        copyEach(
                                removed,
                                machine,
                                kept,
                                tally,
                                (from, to) -> {
                                    final Outcome outcome = copyWorkOut(from, to);
                                    if (outcome == Outcome.COPIED
                                            || outcome == Outcome.UNCHANGED
                                            || outcome == Outcome.NEWER_LEFT) {
                                        retireWork(kept, String.valueOf(from.getFileName()), tally);
                                    }
                                    return outcome;
                                });
                        copyEach(live, machine, kept, tally, KeepCommand::copyWorkOut);
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            err.println("porelo: cannot read " + machine + ": " + ex.getMessage());
            return 70;
        }
        return finishedWork(tally, kept);
    }

    /** Puts kept work records back on a machine, keeping the later update of the same task. */
    private int restoreWork(Path kept, Path machine) {
        final PrintWriter err = spec.commandLine().getErr();
        final List<Path> records;
        try {
            records = workRecords(kept);
        } catch (IOException ex) {
            err.println("porelo: cannot read " + kept + ": " + ex.getMessage());
            return 70;
        }
        if (!prepared(machine)) {
            return 70;
        }
        final Tally tally = new Tally();
        try {
            lockedIfThere(
                    machine,
                    () -> {
                        copyEach(records, kept, machine, tally, KeepCommand::copyWorkIn);
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            err.println("porelo: cannot lock " + machine + ": " + ex.getMessage());
            return 70;
        }
        return finishedWork(tally, machine);
    }

    private int finishedWork(Tally tally, Path to) {
        spec.commandLine().getOut().println(tally.held + " work records in " + to);
        return tally.failed == 0 ? 0 : 70;
    }

    /**
     * Holds the machine's work records' lock while they are read or written, when they are there.
     */
    private static void lockedIfThere(Path machine, FileLocks.Locked<Boolean> locked)
            throws IOException {
        if (Files.isDirectory(machine, LinkOption.NOFOLLOW_LINKS)) {
            FileLocks.holding(machine.resolve(WORK_LOCK), locked);
        } else {
            locked.run();
        }
    }

    /**
     * Drops the copy kept under a task's live name once the removed task's record holds its later
     * update: left there, a restore would give the task twice, and a new task of that name would
     * differ from it at every keep and never be kept. Legacy records are retired by identity alone.
     */
    private void retireWork(Path kept, String removedName, Tally tally) {
        final Path whole = kept.resolve(removedName);
        final Path old = kept.resolve(REMOVED_WORK.matcher(removedName).replaceFirst(".json"));
        try {
            if (Files.isRegularFile(whole, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) {
                final WorkRecord removed = workRecord(readWhole(whole));
                final WorkRecord live = workRecord(readWhole(old));
                if (removed.identity().equals(live.identity()) && replaces(removed, live, true)) {
                    Files.delete(old);
                }
            }
        } catch (IOException ex) {
            tally.failed++;
            spec.commandLine()
                    .getErr()
                    .println("porelo: cannot retire " + old + ": " + ex.getMessage());
        }
    }

    /**
     * Copies a machine's work record out, preferring the machine's when both are legacy records.
     */
    private static Outcome copyWorkOut(Path from, Path to) throws IOException {
        return copyWork(from, to, true);
    }

    /** Copies a kept work record back, preferring the machine's when both are legacy records. */
    private static Outcome copyWorkIn(Path from, Path to) throws IOException {
        return copyWork(from, to, false);
    }

    /**
     * Copies one work record over another. A work record is rewritten whole as its task goes on, so
     * no prefix says which is newer: two of the same task (the same {@code task} and {@code
     * startedAt}) are ordered by {@code updatedAt}. One without an update time is older than one
     * with it; between two without it the machine's copy wins.
     *
     * @param from The record, never followed if a link.
     * @param to Where it goes.
     * @param replacesLegacy Whether the source replaces a legacy copy when both lack an update
     *     time.
     * @return What became of it.
     * @throws IOException If the destination cannot be read or written, or the source is no work
     *     record.
     */
    private static Outcome copyWork(Path from, Path to, boolean replacesLegacy) throws IOException {
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            return Outcome.NOT_COPIED;
        }
        final byte[] record;
        try {
            record = readWhole(from);
        } catch (IOException ex) {
            return Outcome.NOT_COPIED;
        }
        final WorkRecord source = workRecord(record);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(to + " is not a plain file");
            }
            final byte[] existing = readWhole(to);
            if (java.util.Arrays.equals(record, existing)) {
                return Outcome.UNCHANGED;
            }
            final WorkRecord target = workRecord(existing);
            if (!source.identity().equals(target.identity())) {
                return Outcome.DIVERGED;
            }
            if (!replaces(source, target, replacesLegacy)) {
                return Outcome.NEWER_LEFT;
            }
        }
        final Path staged =
                Files.createTempFile(
                        to.getParent(),
                        ".keep-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(staged, record, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.move(
                    staged,
                    to,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return Outcome.COPIED;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** The task's identity and its record's own update time, absent in legacy records. */
    private record WorkRecord(String identity, @Nullable Instant updatedAt) {}

    private static boolean replaces(
            final WorkRecord source, final WorkRecord target, final boolean replacesLegacy) {
        final Instant from = source.updatedAt();
        final Instant to = target.updatedAt();
        if (from == null) {
            return to == null && replacesLegacy;
        }
        return to == null || from.isAfter(to);
    }

    private static WorkRecord workRecord(final byte[] record) throws IOException {
        final Object document;
        try {
            document = Json.parseExact(new String(record, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new IOException("it is no work record", ex);
        }
        if (document instanceof Map<?, ?> fields
                && fields.get("task") instanceof String task
                && fields.get("startedAt") instanceof String startedAt) {
            if (!fields.containsKey("updatedAt")) {
                return new WorkRecord(task + "\n" + startedAt, null);
            }
            if (fields.get("updatedAt") instanceof String updatedAt) {
                try {
                    return new WorkRecord(task + "\n" + startedAt, Instant.parse(updatedAt));
                } catch (DateTimeParseException ex) {
                    throw new IOException("its updatedAt is no instant", ex);
                }
            }
            throw new IOException("its updatedAt is no instant");
        }
        throw new IOException("it is no work record");
    }

    private static byte[] readWhole(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return in.readAllBytes();
        }
    }

    /**
     * Lists the work records of a directory: a lock, a staged write or a temporary file is none.
     */
    private static List<Path> workRecords(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a directory");
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(KeepCommand::isWorkRecord).sorted().toList();
        }
    }

    private static boolean isWorkRecord(Path path) {
        final String name = String.valueOf(path.getFileName());
        return name.endsWith(".json") && !name.startsWith(".");
    }

    private static boolean isRemovedWork(Path path) {
        return REMOVED_WORK.matcher(String.valueOf(path.getFileName())).find();
    }

    /** Copies a machine's records out: a removed task's first, so its old copy can be retired. */
    private int keep(Path machine, Path kept) {
        final PrintWriter err = spec.commandLine().getErr();
        final List<Path> records;
        try {
            records = records(machine);
        } catch (IOException ex) {
            err.println("porelo: cannot read " + machine + ": " + ex.getMessage());
            return 70;
        }
        if (!prepared(kept)) {
            return 70;
        }
        final List<Path> removed = records.stream().filter(KeepCommand::isRemoved).toList();
        final List<Path> live = records.stream().filter(path -> !isRemoved(path)).toList();
        final Tally tally = new Tally();
        copyEach(removed, machine, kept, tally);
        for (final Path record : removed) {
            retire(kept, String.valueOf(record.getFileName()), tally);
        }
        copyEach(live, machine, kept, tally);
        return finished(tally, kept);
    }

    /** Puts kept records back on a machine. */
    private int restore(Path kept, Path machine) {
        final List<Path> records;
        try {
            records = records(kept);
        } catch (IOException ex) {
            spec.commandLine()
                    .getErr()
                    .println("porelo: cannot read " + kept + ": " + ex.getMessage());
            return 70;
        }
        if (!prepared(machine)) {
            return 70;
        }
        final Tally tally = new Tally();
        copyEach(records, kept, machine, tally);
        return finished(tally, machine);
    }

    private boolean prepared(Path to) {
        try {
            createPrivately(to);
            return true;
        } catch (IOException ex) {
            spec.commandLine()
                    .getErr()
                    .println("porelo: cannot use " + to + ": " + ex.getMessage());
            return false;
        }
    }

    private int finished(Tally tally, Path to) {
        spec.commandLine().getOut().println(tally.held + " records in " + to);
        return tally.failed == 0 ? 0 : 70;
    }

    /** How one record is copied over another. */
    @FunctionalInterface
    private interface Copier {
        Outcome copy(Path from, Path to) throws IOException;
    }

    /** Copies each record, going on past one that fails: what can be saved is saved. */
    private void copyEach(List<Path> records, Path from, Path to, Tally tally) {
        copyEach(records, from, to, tally, KeepCommand::copy);
    }

    private void copyEach(List<Path> records, Path from, Path to, Tally tally, Copier copier) {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        for (final Path record : records) {
            final String name = String.valueOf(record.getFileName());
            try {
                final Outcome outcome = copier.copy(record, to.resolve(name));
                switch (outcome) {
                    case NOTHING_COMPLETE -> {}
                    case NOT_COPIED ->
                            err.println(
                                    "porelo: "
                                            + record
                                            + " is not a readable plain file; not copied");
                    case DIVERGED -> {
                        tally.failed++;
                        err.println(
                                "porelo: "
                                        + name
                                        + " differs in "
                                        + from
                                        + " and "
                                        + to
                                        + "; both are left as they are");
                    }
                    case COPIED -> {
                        tally.held++;
                        out.println("copied    " + name);
                    }
                    case UNCHANGED -> {
                        tally.held++;
                        out.println("unchanged " + name);
                    }
                    case LONGER_LEFT -> {
                        tally.held++;
                        out.println("longer    " + name);
                    }
                    case NEWER_LEFT -> {
                        tally.held++;
                        out.println("newer     " + name);
                    }
                }
            } catch (IOException ex) {
                tally.failed++;
                err.println("porelo: cannot copy " + name + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Drops the copy kept under a task's live name once its removed record holds all of it: left
     * there, a restore would give the same requests twice, and a new task of that name would differ
     * from it at every keep and never be kept.
     */
    private void retire(Path kept, String removedName, Tally tally) {
        final Path whole = kept.resolve(removedName);
        final Path old = kept.resolve(REMOVED.matcher(removedName).replaceFirst(".jsonl"));
        try {
            if (Files.isRegularFile(whole, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) {
                final long differs = Files.mismatch(old, whole);
                if (differs == -1 || differs == Files.size(old)) {
                    Files.delete(old);
                }
            }
        } catch (IOException ex) {
            tally.failed++;
            spec.commandLine()
                    .getErr()
                    .println("porelo: cannot retire " + old + ": " + ex.getMessage());
        }
    }

    /** Lists the records of a directory: a temporary file of an interrupted copy is none. */
    private static List<Path> records(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a directory");
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(KeepCommand::isRecord).sorted().toList();
        }
    }

    private static boolean isRecord(Path path) {
        final String name = String.valueOf(path.getFileName());
        return name.endsWith(".jsonl") && !name.startsWith(".");
    }

    private static boolean isRemoved(Path path) {
        return REMOVED.matcher(String.valueOf(path.getFileName())).find();
    }

    private static void createPrivately(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(
                    directory,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a directory");
        }
    }

    /**
     * Copies one record over another unless that would lose lines.
     *
     * @param from The record, never followed if a link.
     * @param to Where it goes.
     * @return What became of it.
     * @throws IOException If either cannot be read or written.
     */
    private static Outcome copy(Path from, Path to) throws IOException {
        // Staged first, since a broker may be appending to the source: what is compared is what
        // is then moved into place.
        final Path staged =
                Files.createTempFile(
                        to.getParent(),
                        ".keep-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            final long length = stage(from, staged);
            if (length == 0) {
                return Outcome.NOTHING_COMPLETE;
            }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(to + " is not a plain file");
                }
                final long existing = Files.size(to);
                final long differs = Files.mismatch(staged, to);
                if (differs == -1) {
                    return Outcome.UNCHANGED;
                }
                if (differs < Math.min(length, existing)) {
                    return Outcome.DIVERGED;
                }
                if (existing > length) {
                    return Outcome.LONGER_LEFT;
                }
            }
            Files.move(
                    staged,
                    to,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return Outcome.COPIED;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static long stage(Path from, Path staged) throws IOException {
        try (FileChannel source =
                        FileChannel.open(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                FileChannel target = FileChannel.open(staged, StandardOpenOption.WRITE)) {
            final long length = completeLength(source);
            long done = 0;
            while (done < length) {
                final long moved = source.transferTo(done, length - done, target);
                if (moved <= 0) {
                    throw new IOException(from + " ended before the length it had");
                }
                done += moved;
            }
            return length;
        }
    }

    /** Counts over the records of one run. */
    private static final class Tally {
        private int held;
        private int failed;
    }

    /** Returns where a record's last complete line ends, without reading all of a long record. */
    private static long completeLength(FileChannel record) throws IOException {
        long end = record.size();
        final ByteBuffer buffer = ByteBuffer.allocate(BACKWARD);
        while (end > 0) {
            final long start = Math.max(0, end - BACKWARD);
            buffer.clear();
            buffer.limit((int) (end - start));
            long at = start;
            while (buffer.hasRemaining()) {
                final int read = record.read(buffer, at);
                if (read < 0) {
                    throw new IOException("the record ended before the length it had");
                }
                at += read;
            }
            for (int i = buffer.position() - 1; i >= 0; i--) {
                if (buffer.get(i) == '\n') {
                    return start + i + 1;
                }
            }
            end = start;
        }
        return 0;
    }
}
