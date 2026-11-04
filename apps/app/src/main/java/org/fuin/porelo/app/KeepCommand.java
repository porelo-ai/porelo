package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Copies the tasks' usage records into a directory it is given, and puts them back on a new
 * machine, so the history a project's velocity is computed from outlives the machine it was written
 * on. Taking the copy elsewhere, and when, is for whoever calls it.
 */
@Command(
        name = "keep",
        mixinStandardHelpOptions = true,
        description = "Copies the usage records into a directory, or puts them back from one.")
public class KeepCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The directory beneath the one given that holds the copies. */
    private static final String USAGE = "usage";

    /** Only a record's complete lines are copied, so its end is read back this much at a time. */
    private static final int BACKWARD = 64 * 1024;

    /** The name a record is given when its task is removed; a new task may take the old one. */
    private static final Pattern REMOVED = Pattern.compile("\\.removed-\\d+\\.jsonl$");

    /** What became of one record. */
    private enum Outcome {
        NOTHING_COMPLETE,
        COPIED,
        UNCHANGED,
        LONGER_LEFT,
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
        final int code;
        if (restore) {
            final String refused = whyATaskForbidsIt();
            if (refused != null) {
                err.println("porelo: " + refused);
                err.flush();
                return 69;
            }
            if (!Files.isDirectory(kept, LinkOption.NOFOLLOW_LINKS)) {
                err.println("porelo: no records were kept in " + kept);
                err.flush();
                return 70;
            }
            code = restore(kept, machine);
        } else {
            code = keep(machine, kept);
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

    /** Copies each record, going on past one that fails: what can be saved is saved. */
    private void copyEach(List<Path> records, Path from, Path to, Tally tally) {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        for (final Path record : records) {
            final String name = String.valueOf(record.getFileName());
            try {
                final Outcome outcome = copy(record, to.resolve(name));
                switch (outcome) {
                    case NOTHING_COMPLETE -> {}
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
