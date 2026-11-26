package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.gate.GitSubprocess;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards a comment's anchor against the exact tree of its side's commit, in real repositories of
 * two commits: lines past a blob, a link that must never be followed, a submodule, a removed file,
 * the old side read at the base, binary and oversized blobs, a wide tree, a tree naming an entry
 * twice, a name the arguments cannot carry, and how lines are counted. What must never be read is
 * observed through a Git that records each call.
 */
class ReviewAnchorsTest {

    /**
     * Runs Git as the gate does and records each call: the size of what it said, a tab, its
     * arguments.
     */
    private static final String RECORDING_GIT =
            """
            #!/bin/sh
            GIT_CONFIG_GLOBAL=/dev/null
            GIT_CONFIG_NOSYSTEM=1
            export GIT_CONFIG_GLOBAL GIT_CONFIG_NOSYSTEM
            said="$0.said.$$"
            git "$@" > "$said"
            code=$?
            printf '%s\\t%s\\n' "$(wc -c < "$said")" "$*" >> "$0.calls"
            cat "$said"
            rm -f "$said"
            exit "$code"
            """;

    @TempDir private Path work;

    @TempDir private Path tools;

    private ReviewAnchors anchors;

    @BeforeEach
    void setUp() {
        anchors = new ReviewAnchors(push -> work.resolve(".git"));
    }

    @Test
    void aRangeInsideTheNewBlobPassesAndOnePastItsEndIsRefused() throws Exception {
        final Push push = twoCommits(Map.of("a.txt", "1\n2\n3\n"), Map.of("a.txt", "1\n2\n3\n4\n"));

        assertThat(anchors.check(push, comment("a.txt", Side.NEW, 1, 4))).isEmpty();
        assertThat(anchors.check(push, comment("a.txt", Side.NEW, 4, 5)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "past the end"));
        assertThat(anchors.check(push, comment("a.txt", Side.NEW, 9, 9)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "past the end"));
    }

    @Test
    void aHeadTheMirrorDoesNotHoldIsNamedNeverReadAsABrokenTree() throws Exception {
        commit(Map.of("a.txt", "1\n"));
        final String absent = "c".repeat(40);

        assertThatThrownBy(
                        () -> anchors.check(push(absent, null), comment("a.txt", Side.NEW, 1, 1)))
                .as("the head the gate never forwarded")
                .isInstanceOfSatisfying(
                        CommitNotInMirror.class,
                        missing -> assertThat(missing.commit()).isEqualTo(absent));
    }

    @Test
    void theOldSideIsReadAtTheBaseAndARemovedFileOnlyThere() throws Exception {
        final Push push =
                twoCommits(
                        Map.of("grown.txt", "1\n", "gone.txt", "1\n2\n"),
                        Map.of("grown.txt", "1\n2\n3\n"));

        assertThat(anchors.check(push, comment("grown.txt", Side.NEW, 3, 3))).isEmpty();
        assertThat(anchors.check(push, comment("grown.txt", Side.OLD, 3, 3)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "past the end"));
        assertThat(anchors.check(push, comment("gone.txt", Side.OLD, 2, 2))).isEmpty();
        assertThat(anchors.check(push, comment("gone.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "no such path"));
    }

    @Test
    void aLinkIsRefusedByNameAndNeverFollowed() throws Exception {
        commit(Map.of("target.txt", "1\n2\n3\n4\n5\n"));
        Files.createSymbolicLink(work.resolve("link.txt"), Path.of("target.txt"));
        Files.createDirectories(work.resolve("real"));
        Files.writeString(work.resolve("real/f.txt"), "1\n2\n");
        Files.createSymbolicLink(work.resolve("dir"), Path.of("real"));
        git("add", "link.txt", "dir", "real/f.txt");
        git("commit", "-q", "-m", "links");
        final Push push = push(rev("HEAD"), rev("HEAD~1"));
        final ReviewAnchors recorded = recorded(StandardCharsets.UTF_8);

        assertThat(recorded.check(push, comment("link.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "link"));
        assertThat(recorded.check(push, comment("dir/f.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "link"));
        // Neither a link's own blob nor what it points to is read.
        assertThat(calls()).isNotEmpty().noneMatch(Call::readsBlob);
        assertThat(recorded.check(push, comment("real/f.txt", Side.NEW, 1, 2))).isEmpty();
        // The recorder sees a blob being read.
        assertThat(calls()).anyMatch(Call::readsBlob);
    }

    @Test
    void aSubmoduleAndADirectoryAreNotRegularFiles() throws Exception {
        commit(Map.of("d/inner.txt", "1\n"));
        git("update-index", "--add", "--cacheinfo", "160000," + rev("HEAD") + ",sub");
        git("commit", "-q", "-m", "submodule");
        final Push push = push(rev("HEAD"), rev("HEAD~1"));

        assertThat(anchors.check(push, comment("sub", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "submodule"));
        assertThat(anchors.check(push, comment("sub/x.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "no such path"));
        assertThat(anchors.check(push, comment("d", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "directory"));
        assertThat(anchors.check(push, comment("d/inner.txt", Side.NEW, 1, 1))).isEmpty();
    }

    @Test
    void anEmptyBlobAndAMissingBaseHoldNoLine() throws Exception {
        commit(Map.of("empty.txt", ""));
        final Push push = push(rev("HEAD"), null);

        assertThat(anchors.check(push, comment("empty.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "past the end"));
        assertThat(anchors.check(push, comment("empty.txt", Side.OLD, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "no base"));
    }

    @Test
    void aBinaryBlobIsNotARegularFile() throws Exception {
        commitBytes("nul.bin", new byte[] {'a', '\n', 0, '\n'});
        commitBytes("latin1.txt", "café\n".getBytes(StandardCharsets.ISO_8859_1));
        final Push push = push(rev("HEAD"), rev("HEAD~1"));

        assertThat(anchors.check(push, comment("nul.bin", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "binary"));
        assertThat(anchors.check(push, comment("latin1.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "binary"));
    }

    @Test
    void aBlobOverTenMiBIsRefusedBeforeItIsRead() throws Exception {
        final byte[] large = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(large, (byte) '\n');
        commitBytes("large.txt", large);
        commitBytes("exact.txt", java.util.Arrays.copyOf(large, 10 * 1024 * 1024));
        final Push push = push(rev("HEAD"), rev("HEAD~1"));
        final ReviewAnchors recorded = recorded(StandardCharsets.UTF_8);

        assertThat(recorded.check(push, comment("large.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.TOO_LARGE, "blob 10 MiB"));
        assertThat(calls()).isNotEmpty().noneMatch(Call::readsBlob);
        assertThat(recorded.check(push, comment("exact.txt", Side.NEW, 1, 1))).isEmpty();
        // The recorder sees a blob being read.
        assertThat(calls()).anyMatch(Call::readsBlob);
    }

    @Test
    void aWideTreeIsAskedForOneNameAndNeverListedWhole() throws Exception {
        final String blob = hashObject("1\n2\n".getBytes(StandardCharsets.UTF_8));
        final StringBuilder wide = new StringBuilder();
        for (int index = 0; index < 8000; index++) {
            wide.append("100644 blob ").append(blob).append('\t');
            wide.append(String.format("name-%05d", index)).append('\n');
        }
        final String inner = mktree(wide + "100644 blob " + blob + "\ta.txt\n");
        final String root = mktree(wide + "040000 tree " + inner + "\tdir\n");
        final Push push = push(commitTree(root), null);
        // Listed whole, each of the two trees says several hundred KiB.
        assertThat(run(false, "ls-tree", "-z", "-l", inner).length()).isGreaterThan(256 * 1024);
        final ReviewAnchors recorded = recorded(StandardCharsets.UTF_8);

        assertThat(recorded.check(push, comment("dir/a.txt", Side.NEW, 1, 2))).isEmpty();
        assertThat(calls())
                .anyMatch(call -> call.arguments().contains(" ls-tree "))
                .allMatch(call -> call.said() <= 4 * 1024, "said at most 4 KiB");
    }

    @Test
    void aTreeThatNamesAnEntryTwiceIsRefused() throws Exception {
        final String blob = hashObject("1\n".getBytes(StandardCharsets.UTF_8));
        final byte[] entry = treeEntry("100644", "a.txt", blob);
        final byte[] twice = Arrays.copyOf(entry, entry.length * 2);
        System.arraycopy(entry, 0, twice, entry.length, entry.length);
        final String tree =
                run(twice, "hash-object", "-t", "tree", "--literally", "-w", "--stdin").strip();
        final Push push = push(commitTree(tree), null);

        assertThat(anchors.check(push, comment("a.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.NOT_A_REGULAR_FILE, "duplicate entry"));
    }

    @Test
    void aNameTheArgumentsCannotCarryIsStillFound() throws Exception {
        final String blob = hashObject("1\n".getBytes(StandardCharsets.UTF_8));
        // Built from standard input, so no argument of this test's own holds the name either.
        final String tree = mktree("100644 blob " + blob + "\tcafé.txt\n");
        final Push push = push(commitTree(tree), null);
        final ReviewAnchors ascii = recorded(StandardCharsets.US_ASCII);

        assertThat(ascii.check(push, comment("café.txt", Side.NEW, 1, 1))).isEmpty();
        assertThat(ascii.check(push, comment("cafe.txt", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.ANCHOR_OUTSIDE_BLOB, "no such path"));
        // The default takes the encoding this machine gives arguments in.
        assertThat(anchors.check(push, comment("café.txt", Side.NEW, 1, 1))).isEmpty();
    }

    @Test
    void linesAreCountedAsGitsDiffCountsThem() throws Exception {
        final Push push =
                twoCommits(
                        Map.of("x", "x\n"),
                        Map.of(
                                "no-final.txt", "a\nb",
                                "crlf.txt", "a\r\nb\r\n",
                                "lone-cr.txt", "a\rb\n",
                                "run.sh", "#!/bin/sh\necho\n"));
        git("update-index", "--chmod=+x", "run.sh");
        git("commit", "-q", "-m", "executable");
        final Push executable = push(rev("HEAD"), push.base());

        // The last line without a line feed is a line, as diff shows it with "No newline".
        assertThat(anchors.check(push, comment("no-final.txt", Side.NEW, 2, 2))).isEmpty();
        assertThat(anchors.check(push, comment("no-final.txt", Side.NEW, 3, 3))).isNotEmpty();
        // Only a line feed ends a line; a carriage return before it is part of the line.
        assertThat(anchors.check(push, comment("crlf.txt", Side.NEW, 1, 2))).isEmpty();
        assertThat(anchors.check(push, comment("crlf.txt", Side.NEW, 3, 3))).isNotEmpty();
        assertThat(anchors.check(push, comment("lone-cr.txt", Side.NEW, 1, 1))).isEmpty();
        assertThat(anchors.check(push, comment("lone-cr.txt", Side.NEW, 2, 2))).isNotEmpty();
        assertThat(anchors.check(executable, comment("run.sh", Side.NEW, 1, 2))).isEmpty();
    }

    @Test
    void theFieldsAreCheckedBeforeAnyGitAndAReplyNeedsNoTree() {
        final ReviewAnchors noMirror =
                new ReviewAnchors(
                        push -> {
                            throw new AssertionError("git reached");
                        });
        final Push push = push("a".repeat(40), null);

        assertThat(noMirror.check(push, comment("../../etc/passwd", Side.NEW, 1, 1)))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "path: dot component"));
        assertThat(noMirror.check(push, new ReviewReply("t", new Body("Fixed.")))).isEmpty();
        assertThat(noMirror.check(push, new ReviewReply("t", new Body("x‮"))))
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "format character"));
    }

    private static ReviewComment comment(
            final String path, final Side side, final int start, final int end) {
        return new ReviewComment(new LineAnchor(path, side, start, end), new Body("why"), null);
    }

    private Push twoCommits(final Map<String, String> base, final Map<String, String> head)
            throws Exception {
        commit(base);
        for (final String path : base.keySet()) {
            if (!head.containsKey(path)) {
                git("rm", "-q", path);
            }
        }
        commit(head);
        return push(rev("HEAD"), rev("HEAD~1"));
    }

    private static Push push(final String head, final @Nullable String base) {
        return new Push("project", "repo", "main", head, base, "0123456789ab");
    }

    private void commit(final Map<String, String> files) throws Exception {
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
            git("add", file.getKey());
        }
        if (rev("--verify", "-q", "HEAD").isEmpty()) {
            git("commit", "-q", "--allow-empty", "-m", "first");
        } else {
            git("commit", "-q", "--allow-empty", "-m", "next");
        }
    }

    private void commitBytes(final String name, final byte[] content) throws Exception {
        Files.write(work.resolve(name), content);
        git("add", name);
        git("commit", "-q", "-m", name);
    }

    private String rev(final String... arguments) throws Exception {
        final List<String> all = new ArrayList<>(List.of("rev-parse"));
        all.addAll(List.of(arguments));
        return run(false, all.toArray(String[]::new)).strip();
    }

    private void git(final String... arguments) throws Exception {
        run(true, arguments);
    }

    private String hashObject(final byte[] content) throws Exception {
        return run(content, "hash-object", "-w", "--stdin").strip();
    }

    /** Makes a tree from {@code ls-tree} lines, sorted by Git. */
    private String mktree(final String lines) throws Exception {
        return run(lines.getBytes(StandardCharsets.UTF_8), "mktree").strip();
    }

    private String commitTree(final String tree) throws Exception {
        return run(true, "commit-tree", tree, "-m", "tree").strip();
    }

    /** One raw entry of a tree object: mode, name, and the object id in bytes. */
    private static byte[] treeEntry(final String mode, final String name, final String object) {
        final byte[] head = (mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
        final byte[] id = HexFormat.of().parseHex(object);
        final byte[] entry = Arrays.copyOf(head, head.length + id.length);
        System.arraycopy(id, 0, entry, head.length, id.length);
        return entry;
    }

    /**
     * Returns anchors whose Git records each call in {@link #calls()}, run without the account's
     * and the system's configuration as the gate runs it.
     */
    private ReviewAnchors recorded(final Charset arguments) throws IOException {
        final Path script = tools.resolve("git");
        Files.writeString(script, RECORDING_GIT);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return new ReviewAnchors(
                push -> work.resolve(".git"), new GitSubprocess(script.toString(), 60), arguments);
    }

    /** The calls the recording Git saw so far, in order. */
    private List<Call> calls() throws IOException {
        final Path log = tools.resolve("git.calls");
        if (!Files.exists(log)) {
            return List.of();
        }
        final List<Call> calls = new ArrayList<>();
        for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            final int tab = line.indexOf('\t');
            calls.add(
                    new Call(
                            Long.parseLong(line.substring(0, tab).strip()),
                            line.substring(tab + 1)));
        }
        return calls;
    }

    /**
     * One call of the recording Git.
     *
     * @param said How many bytes Git wrote to its standard output.
     * @param arguments Its arguments, joined by spaces.
     */
    private record Call(long said, String arguments) {

        boolean readsBlob() {
            return arguments.contains(" cat-file blob ");
        }
    }

    private String run(final boolean mustSucceed, final String... arguments) throws Exception {
        return run(mustSucceed, new byte[0], arguments);
    }

    private String run(final byte[] input, final String... arguments) throws Exception {
        return run(true, input, arguments);
    }

    private String run(final boolean mustSucceed, final byte[] input, final String... arguments)
            throws Exception {
        if (!Files.isDirectory(work.resolve(".git"))) {
            start(List.of("git", "init", "-q", "--initial-branch=main", work.toString()), true);
        }
        final List<String> command = new ArrayList<>(List.of("git", "-C", work.toString()));
        command.addAll(List.of(arguments));
        return start(command, mustSucceed, input);
    }

    private static String start(final List<String> command, final boolean mustSucceed)
            throws IOException, InterruptedException {
        return start(command, mustSucceed, new byte[0]);
    }

    private static String start(
            final List<String> command, final boolean mustSucceed, final byte[] input)
            throws IOException, InterruptedException {
        final ProcessBuilder builder =
                new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment()
                .putAll(
                        Map.of(
                                "GIT_AUTHOR_NAME", "Test",
                                "GIT_AUTHOR_EMAIL", "test@example.com",
                                "GIT_COMMITTER_NAME", "Test",
                                "GIT_COMMITTER_EMAIL", "test@example.com",
                                "GIT_TERMINAL_PROMPT", "0",
                                "GIT_CONFIG_GLOBAL", "/dev/null",
                                "GIT_CONFIG_NOSYSTEM", "1"));
        final Process process = builder.start();
        // Every command given input here reads all of it before it answers, so it is written whole
        // first.
        try (java.io.OutputStream in = process.getOutputStream()) {
            in.write(input);
        }
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("git timed out: " + command);
        }
        if (mustSucceed && process.exitValue() != 0) {
            throw new IOException("git exited " + process.exitValue() + ": " + command);
        }
        return output;
    }
}
