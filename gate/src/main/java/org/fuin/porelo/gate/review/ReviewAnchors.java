package org.fuin.porelo.gate.review;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.fuin.porelo.gate.GitSubprocess;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * Checks a comment against the exact Git tree of its side's commit: the waiting commit for the new
 * side, its base for the old. Its fields are judged first by {@link CommentFields}, so a path that
 * breaks a rule never reaches Git; a reply is judged by its text alone.
 *
 * <p>The anchor's path must name a regular blob ({@code 100644} or {@code 100755}) holding text,
 * and its lines must be in that blob. A link, at the path or at any directory above it, is refused
 * by name and never followed; its target is never read. The tree is walked one directory at a time
 * by the object ids Git lists, asking for one name at a time as a literal pathspec and comparing
 * each listed name byte for byte, so no pathspec magic, no {@code <commit>:<path>} and no name
 * holding a slash in a crafted tree can lead elsewhere. A listing is read one entry at a time and
 * never held whole, however wide the tree. A blob's size is read from the listing first, and one
 * over {@link #MAX_BLOB_BYTES} is never read.
 *
 * <p>Lines are counted as Git's diff counts them: only a line feed ends a line, a carriage return
 * before it belongs to the line, and a last line without a line feed is a line.
 *
 * <p>Whether a producer may write into a thread is not judged here, but where the item is taken in.
 * A Git that cannot be run or read fails loudly, never as a refusal of the item.
 */
public final class ReviewAnchors implements CommentCheck {

    /** How large a blob may be before it is refused unread. */
    public static final long MAX_BLOB_BYTES = 10L * 1024 * 1024;

    private static final String OBJECT_ID = "[0-9a-f]{40}|[0-9a-f]{64}";

    private static final String REGULAR = "100644";

    private static final String EXECUTABLE = "100755";

    private static final String LINK = "120000";

    private static final String SUBMODULE = "160000";

    private static final String TREE = "040000";

    private static final byte[] NOTHING = new byte[0];

    /**
     * How many bytes an entry of {@code ls-tree -z -l} holds at most before its name: mode, type,
     * object id, size and their separators.
     */
    private static final int ENTRY_BYTES = 128;

    private final Function<Push, Path> mirrors;

    private final GitSubprocess git;

    /** The encoding a program's arguments are given in. */
    private final Charset arguments;

    /**
     * Constructor with the default limits.
     *
     * @param mirrors Answers the mirror that holds a push's commits.
     */
    public ReviewAnchors(final Function<Push, Path> mirrors) {
        this(mirrors, LimitFactors.DEFAULT);
    }

    /**
     * Constructor.
     *
     * @param mirrors Answers the mirror that holds a push's commits.
     * @param limits The account's factors for how long Git may take.
     */
    public ReviewAnchors(final Function<Push, Path> mirrors, final LimitFactors limits) {
        this(mirrors, GitSubprocess.withoutConfig(limits), argumentEncoding());
    }

    /**
     * Constructor with the Git to run, for a test that records what is asked of it.
     *
     * @param mirrors Answers the mirror that holds a push's commits.
     * @param git Runs Git.
     * @param arguments The encoding a program's arguments are given in.
     */
    ReviewAnchors(
            final Function<Push, Path> mirrors, final GitSubprocess git, final Charset arguments) {
        this.mirrors = Objects.requireNonNull(mirrors, "mirrors");
        this.git = Objects.requireNonNull(git, "git");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    /**
     * Returns the encoding this machine gives a program's arguments in, or US-ASCII when it does
     * not say: a name that encoding cannot carry is never given as an argument.
     */
    private static Charset argumentEncoding() {
        final String name = System.getProperty("sun.jnu.encoding");
        if (name == null) {
            return StandardCharsets.US_ASCII;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException ex) {
            return StandardCharsets.US_ASCII;
        }
    }

    @Override
    public Optional<ReviewRefusal> check(final Push push, final ReviewItem item) {
        final Optional<ReviewRefusal> fields = CommentFields.check(item);
        if (fields.isPresent()) {
            return fields;
        }
        return switch (item) {
            case ReviewComment comment -> anchor(push, comment.anchor());
            case ReviewReply reply -> Optional.empty();
            case ForgeReply reply -> Optional.empty();
            case ThreadResolve resolve -> Optional.empty();
        };
    }

    private Optional<ReviewRefusal> anchor(final Push push, final Anchor anchor) {
        return switch (anchor) {
            case LineAnchor line -> lineAnchor(push, line);
        };
    }

    private Optional<ReviewRefusal> lineAnchor(final Push push, final LineAnchor anchor) {
        final String commit = anchor.side() == Side.NEW ? push.head() : push.base();
        if (commit == null) {
            return refused(ReviewError.ANCHOR_OUTSIDE_BLOB, "no base");
        }
        final Path mirror = mirrors.apply(push);
        try {
            final List<String> components = List.of(anchor.path().split("/"));
            String tree = commit;
            Entry entry = null;
            for (int index = 0; index < components.size(); index++) {
                entry = entry(mirror, tree, components.get(index));
                if (entry == null) {
                    return refused(ReviewError.ANCHOR_OUTSIDE_BLOB, "no such path");
                }
                if (entry.mode().equals(LINK)) {
                    return refused(ReviewError.NOT_A_REGULAR_FILE, "link");
                }
                if (index < components.size() - 1) {
                    if (!entry.mode().equals(TREE)) {
                        return refused(ReviewError.ANCHOR_OUTSIDE_BLOB, "no such path");
                    }
                    tree = entry.object();
                }
            }
            final Entry file = Objects.requireNonNull(entry, "a path has at least one component");
            return blob(mirror, file, anchor);
        } catch (DuplicateEntry ex) {
            return refused(ReviewError.NOT_A_REGULAR_FILE, "duplicate entry");
        } catch (IOException ex) {
            if (!holds(mirror, commit)) {
                throw new CommitNotInMirror(commit);
            }
            throw new UncheckedIOException("The side's tree could not be read", ex);
        }
    }

    /**
     * Returns whether the mirror holds the commit, asked only after its tree could not be read: Git
     * answers a missing object as {@code missing}, so a commit the gate never forwarded is told
     * apart from a mirror that cannot be read at all.
     */
    private boolean holds(final Path mirror, final String commit) {
        final byte[] answer;
        try {
            answer =
                    git.runOrFail(
                            List.of("--git-dir=" + mirror, "cat-file", "--batch-check"),
                            (commit + "^{commit}\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ex) {
            // Git did not answer at all: the mirror is unreadable, not missing a commit.
            return true;
        }
        return !new String(answer, StandardCharsets.US_ASCII).strip().endsWith(" missing");
    }

    private Optional<ReviewRefusal> blob(
            final Path mirror, final Entry file, final LineAnchor anchor) throws IOException {
        switch (file.mode()) {
            case REGULAR, EXECUTABLE -> {
                // Checked below.
            }
            case SUBMODULE -> {
                return refused(ReviewError.NOT_A_REGULAR_FILE, "submodule");
            }
            case TREE -> {
                return refused(ReviewError.NOT_A_REGULAR_FILE, "directory");
            }
            default -> {
                return refused(ReviewError.NOT_A_REGULAR_FILE, "mode");
            }
        }
        if (file.size() > MAX_BLOB_BYTES) {
            return refused(ReviewError.TOO_LARGE, "blob 10 MiB");
        }
        final byte[] content =
                git.runOrFail(
                        List.of(
                                "--literal-pathspecs",
                                "--git-dir=" + mirror,
                                "cat-file",
                                "blob",
                                file.object()),
                        NOTHING);
        if (content.length != file.size()) {
            throw new IOException("A blob's length differs from its listed size");
        }
        if (!text(content)) {
            return refused(ReviewError.NOT_A_REGULAR_FILE, "binary");
        }
        if (anchor.endLine() > lines(content)) {
            return refused(ReviewError.ANCHOR_OUTSIDE_BLOB, "past the end");
        }
        return Optional.empty();
    }

    /**
     * Returns the entry of one name in one tree, or null: the tree's own entries only, by their
     * object id, each name compared byte for byte.
     *
     * <p>The name is asked for as a literal pathspec, so Git lists only the entries of that name
     * and a wide tree is never listed whole. A name the argument encoding cannot carry unchanged is
     * not given to Git; the whole tree is listed instead. Either way the listing is read one entry
     * at a time and only an entry of the name is kept, so no listing is held in memory.
     */
    private @Nullable Entry entry(final Path mirror, final String tree, final String name)
            throws IOException, DuplicateEntry {
        final byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "--literal-pathspecs",
                                "--git-dir=" + mirror,
                                "ls-tree",
                                "-z",
                                "-l",
                                "--full-tree",
                                tree));
        if (Arrays.equals(name.getBytes(arguments), wanted)) {
            command.add("--");
            command.add(name);
        }
        final Lookup lookup = new Lookup(wanted);
        git.streamOrFail(command, lookup);
        return lookup.found();
    }

    /**
     * Reads one entry of {@code ls-tree -z -l}: {@code <mode> SP <type> SP <object> SP+ <size> TAB
     * <name>}, the size {@code -} for what is no blob.
     */
    private static Entry entry(final byte[] record, final int length) throws IOException {
        int tab = 0;
        while (tab < length && record[tab] != '\t') {
            tab++;
        }
        if (tab == length) {
            throw new IOException("A tree entry without a name");
        }
        final String[] meta =
                new String(record, 0, tab, StandardCharsets.US_ASCII).trim().split(" +");
        if (meta.length != 4 || !meta[2].matches(OBJECT_ID)) {
            throw new IOException("A tree entry that is not one");
        }
        final long size;
        try {
            size = meta[3].equals("-") ? -1 : Long.parseLong(meta[3]);
        } catch (NumberFormatException ex) {
            throw new IOException("A tree entry's size is no number", ex);
        }
        return new Entry(meta[0], meta[2], size, Arrays.copyOfRange(record, tab + 1, length));
    }

    /** Whether a blob is text: no NUL byte, and valid UTF-8. */
    private static boolean text(final byte[] content) {
        for (final byte each : content) {
            if (each == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    /** Counts lines as Git's diff does: line feeds, and a last line without one. */
    private static long lines(final byte[] content) {
        long lines = 0;
        for (final byte each : content) {
            if (each == '\n') {
                lines++;
            }
        }
        if (content.length > 0 && content[content.length - 1] != '\n') {
            lines++;
        }
        return lines;
    }

    private static Optional<ReviewRefusal> refused(final ReviewError error, final String detail) {
        return Optional.of(new ReviewRefusal(error, detail));
    }

    /**
     * One entry of a tree.
     *
     * @param mode Its mode, six digits.
     * @param object Its object id.
     * @param size Its size in bytes, or -1 for what is no blob.
     * @param name Its name, as Git keeps it.
     */
    private record Entry(String mode, String object, long size, byte[] name) {}

    /**
     * Takes a tree listing as Git writes it and keeps only the entry of one name. An entry is held
     * up to {@link #ENTRY_BYTES} beyond the name; a longer one holds a longer name, since what
     * comes before the name is shorter, and is passed over unkept.
     */
    private static final class Lookup extends OutputStream {

        private final byte[] wanted;

        private final byte[] record;

        private int length;

        private boolean longer;

        private @Nullable Entry found;

        private boolean twice;

        Lookup(final byte[] wanted) {
            this.wanted = wanted;
            this.record = new byte[wanted.length + ENTRY_BYTES];
        }

        @Override
        public void write(final int each) throws IOException {
            take((byte) each);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int count)
                throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            for (int index = offset; index < offset + count; index++) {
                take(bytes[index]);
            }
        }

        private void take(final byte each) throws IOException {
            if (each == 0) {
                if (!longer) {
                    final Entry entry = entry(record, length);
                    if (Arrays.equals(entry.name(), wanted)) {
                        if (found == null) {
                            found = entry;
                        } else {
                            twice = true;
                        }
                    }
                }
                length = 0;
                longer = false;
            } else if (length < record.length) {
                record[length++] = each;
            } else {
                longer = true;
            }
        }

        /** Returns the entry of the name, or null, once Git has written the whole listing. */
        @Nullable Entry found() throws IOException, DuplicateEntry {
            if (length > 0 || longer) {
                throw new IOException("A tree listing that ends inside an entry");
            }
            if (twice) {
                throw new DuplicateEntry();
            }
            return found;
        }
    }

    /** A tree that names one entry twice, which only a crafted tree does. */
    private static final class DuplicateEntry extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
