package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.junit.jupiter.api.Test;

/**
 * A review's history and patch as git answers them through a runner that keeps only a long output's
 * end: a long history is read in windows that each come back whole, and a patch and its file lists
 * are read whole from the file git writes them to, never from that kept end.
 */
class ReviewWindowsTest {

    /** More commits than one window reads. */
    private static final int COMMITS = 12_000;

    /** Above this many commits, the fake runner answers as a cut output, its start lost. */
    private static final int KEPT = 3_000;

    private static final String CUT = "[1234 bytes cut before this]\n";

    private final List<List<String>> asked = new CopyOnWriteArrayList<>();

    /** Above this many commits, the answer of whole messages alone comes back cut. */
    private int keptMessages = Integer.MAX_VALUE;

    private static String id(int i) {
        return "%040x".formatted(i);
    }

    /** Answers {@code git log} over a history of {@link #COMMITS}, newest first. */
    private CommandResult log(Command command) {
        final List<String> arguments = command.arguments();
        asked.add(arguments);
        if (arguments.contains("rev-list")) {
            return new CommandResult(command, 0, COMMITS + "\n", "");
        }
        int skip = 0;
        int most = Integer.MAX_VALUE;
        boolean nulls = false;
        String format = "";
        for (final String argument : arguments) {
            if (argument.startsWith("--skip=")) {
                skip = Integer.parseInt(argument.substring("--skip=".length()));
            } else if (argument.matches("-\\d+")) {
                most = Integer.parseInt(argument.substring(1));
            } else if ("-z".equals(argument)) {
                nulls = true;
            } else if (argument.startsWith("--format=")) {
                format = argument.substring("--format=".length());
            }
        }
        final StringBuilder out = new StringBuilder();
        final int end = (int) Math.min((long) skip + most, COMMITS);
        for (int i = skip; i < end; i++) {
            final String id = id(i);
            final String record =
                    format.replace("%H", id)
                            .replace("%an <%ae>", "A <a@example.com>")
                            .replace("%cn <%ce>", "C <c@example.com>")
                            .replace("%s", "subject " + i)
                            .replace("%B", "subject " + i + "\n")
                            .replace("%(trailers:only,unfold)", "")
                            .replace("%n", "\n");
            out.append(record).append(nulls ? "\0" : "\n");
        }
        final String whole = out.toString();
        if (end - skip > KEPT || format.contains("%B") && end - skip > keptMessages) {
            // Its start lost, as the runner keeps a long output's end.
            return new CommandResult(command, 0, CUT + whole.substring(whole.length() / 2), "");
        }
        return new CommandResult(command, 0, whole, "");
    }

    private GitGate gate(CommandRunner runner) {
        return new GitGate(runner, Path.of("/nonexistent/mirror.git"), GateMode.OFFLINE, null);
    }

    @Test
    void aHistoryLongerThanOneAnswerIsReadWholeInWindows() {
        final GitGate gate = gate(this::log);
        final GitGate.Waiting head = new GitGate.Waiting(id(0), "subject 0");

        final String log = gate.log(head, null);

        final StringBuilder expected = new StringBuilder();
        for (int i = 0; i < COMMITS; i++) {
            expected.append(id(i)).append(" subject ").append(i).append('\n');
        }
        assertThat(log).as("every commit once, in order, none cut").isEqualTo(expected.toString());
        assertThat(log).doesNotContain("bytes cut before this");
    }

    @Test
    void theCommitsOfALongHistoryAreReadWholeInWindows() {
        final GitGate gate = gate(this::log);
        final GitGate.Waiting head = new GitGate.Waiting(id(0), "subject 0");

        final List<GitGate.Made> made = gate.made(head, null);

        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < COMMITS; i++) {
            ids.add(id(i));
        }
        assertThat(made.stream().map(GitGate.Made::commit).toList()).isEqualTo(ids);
        assertThat(made.get(7).message()).isEqualTo("subject 7\n");
    }

    @Test
    void messagesThatComeBackCutHalveTheWindowRatherThanComeBackEmpty() {
        // The four-line answer is whole; only the whole messages are not.
        keptMessages = 1_000;
        final GitGate gate = gate(this::log);

        final List<GitGate.Made> made = gate.made(new GitGate.Waiting(id(0), "subject 0"), null);

        assertThat(made).hasSize(COMMITS);
        for (int i = 0; i < COMMITS; i++) {
            assertThat(made.get(i).message()).as("message %d", i).isEqualTo("subject " + i + "\n");
        }
    }

    @Test
    void aReviewReadingMoreThanItsBoundIsRefusedNamingItsCommits() {
        final GitGate gate = gate(this::log).readingAtMost(100_000);
        final GitGate.Waiting head = new GitGate.Waiting(id(0), "subject 0");

        assertThatThrownBy(() -> gate.log(head, null))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("too large to read")
                .hasMessageContaining(String.valueOf(COMMITS));
        assertThatThrownBy(() -> gate.made(head, null))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("too large to read")
                .hasMessageContaining(String.valueOf(COMMITS));
    }

    /** A patch larger than the runner keeps of a command's standard output. */
    private static final int LARGE_PATCH = 17 * 1024 * 1024;

    private static final String BLOB_A = "a".repeat(40);

    private static final String BLOB_B = "b".repeat(40);

    /**
     * Answers a review's diffs as git does with {@code --output}: the whole answer in the file it
     * names, and on standard output only what the runner would keep of a long answer, so a review
     * that read standard output would show the cut tail.
     */
    private CommandRunner diffs(final List<String> paths, final String patch) {
        return command -> {
            final List<String> arguments = command.arguments();
            asked.add(arguments);
            if (arguments.contains("hash-object")) {
                return new CommandResult(command, 0, "4b825dc\n", "");
            }
            if (arguments.contains("--shortstat")) {
                return new CommandResult(
                        command, 0, " " + paths.size() + " files changed, 9 insertions(+)\n", "");
            }
            final StringBuilder answer = new StringBuilder();
            if (arguments.contains("--numstat")) {
                paths.forEach(path -> answer.append("1\t0\t").append(path).append('\0'));
            } else if (arguments.contains("--raw")) {
                paths.forEach(
                        path ->
                                answer.append(":100644 100644 ")
                                        .append(BLOB_A)
                                        .append(' ')
                                        .append(BLOB_B)
                                        .append(" M\0")
                                        .append(path)
                                        .append('\0'));
            } else if (arguments.contains("--patch")) {
                answer.append(patch);
            } else {
                return new CommandResult(command, 0, "", "");
            }
            final String whole = answer.toString();
            for (final String argument : arguments) {
                if (argument.startsWith("--output=")) {
                    try {
                        Files.writeString(
                                Path.of(argument.substring("--output=".length())),
                                whole,
                                StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
            return new CommandResult(
                    command, 0, CUT + whole.substring(Math.max(0, whole.length() - 16)), "");
        };
    }

    private static String patchOf(final int size) {
        final StringBuilder patch =
                new StringBuilder(
                        "diff --git a/data.csv b/data.csv\n--- a/data.csv\n+++ b/data.csv\n");
        while (patch.length() < size) {
            patch.append("+one more line of a large data file\n");
        }
        return patch.toString();
    }

    @Test
    void aPatchLargerThanTheRunnerKeepsIsAnsweredWhole() {
        final String patch = patchOf(LARGE_PATCH);
        final GitGate gate = gate(diffs(List.of("data.csv"), patch));

        final ReviewRanking.Review review =
                gate.rankedReview(new GitGate.Waiting(id(0), "subject 0"), null, false);

        assertThat(review.patch().length())
                .as("every character of the patch")
                .isEqualTo(patch.length());
        assertThat(review.patch()).as("from its start, never the kept tail").isEqualTo(patch);
        assertThat(review.files()).extracting(ReviewRanking.File::path).containsExactly("data.csv");
    }

    @Test
    void aPatchPastTheBoundIsRefusedNamingItsSizeAndItsFiles() {
        final String patch = patchOf(5_000);
        final GitGate gate =
                gate(diffs(List.of("data.csv", "other.csv"), patch)).readingAtMost(2_000);

        assertThatThrownBy(
                        () ->
                                gate.rankedReview(
                                        new GitGate.Waiting(id(0), "subject 0"), null, false))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("too large to read")
                .hasMessageContaining(patch.getBytes(StandardCharsets.UTF_8).length + " bytes")
                .hasMessageContaining("2 changed files")
                .hasMessageContaining("2000 characters");
    }

    @Test
    void aChangedPathHoldingALineFeedKeepsItsName() {
        final String path = "two\nlines.txt";
        final GitGate gate = gate(diffs(List.of(path, "plain.txt"), patchOf(100)));

        final ReviewRanking.Review review =
                gate.rankedReview(new GitGate.Waiting(id(0), "subject 0"), null, false);

        assertThat(review.files())
                .extracting(ReviewRanking.File::path)
                .containsExactlyInAnyOrder(path, "plain.txt");
        assertThat(review.files())
                .filteredOn(file -> file.path().equals(path))
                .singleElement()
                .extracting(ReviewRanking.File::added)
                .isEqualTo(1);
    }
}
