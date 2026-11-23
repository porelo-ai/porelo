package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What git is asked for one review, through a runner that answers as git does: the patch is read in
 * git's own order and put in the ranking's order here, and the heads of the changed files come from
 * one {@code cat-file --batch}, never one program per file.
 */
class ReviewReadingTest {

    private final List<List<String>> asked = new CopyOnWriteArrayList<>();

    /** Each changed file: its path, its new blob and that blob's content, in git's order. */
    private final Map<String, String[]> changed = new LinkedHashMap<>();

    private static String blob(final int i) {
        return "%040x".formatted(i + 1);
    }

    private void change(final String path, final String content) {
        changed.put(path, new String[] {blob(changed.size()), content});
    }

    private static String part(final String path) {
        return "diff --git a/" + path + " b/" + path + "\n@@ -0,0 +1 @@\n+" + path + "\n";
    }

    private CommandResult answer(final Command command) {
        final List<String> arguments = command.arguments();
        asked.add(arguments);
        if (arguments.contains("cat-file")) {
            return catFile(command);
        }
        final StringBuilder answer = new StringBuilder();
        if (arguments.contains("--numstat")) {
            changed.keySet().forEach(path -> answer.append("1\t0\t").append(path).append('\0'));
        } else if (arguments.contains("--raw")) {
            changed.forEach(
                    (path, blob) ->
                            answer.append(":000000 100644 ")
                                    .append("0".repeat(40))
                                    .append(' ')
                                    .append(blob[0])
                                    .append(" A\0")
                                    .append(path)
                                    .append('\0'));
        } else if (arguments.contains("--patch")) {
            // In git's order whatever is asked: an order file, were one passed, is not read here.
            changed.keySet().forEach(path -> answer.append(part(path)));
        }
        for (final String argument : arguments) {
            if (argument.startsWith("--output=")) {
                try {
                    Files.writeString(
                            Path.of(argument.substring("--output=".length())),
                            answer,
                            StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
        return new CommandResult(command, 0, "", "");
    }

    /** Answers {@code cat-file} as git does, its batch forms reading their objects from input. */
    private CommandResult catFile(final Command command) {
        final List<String> arguments = command.arguments();
        final String last = arguments.getLast();
        if (arguments.contains("-p")) {
            return new CommandResult(command, 0, content(last), "");
        }
        final StringBuilder out = new StringBuilder();
        final String input = command.input() == null ? "" : command.input();
        for (final String line : input.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            final int space = line.indexOf(' ');
            final String object = space < 0 ? line : line.substring(0, space);
            final String rest = space < 0 ? "" : line.substring(space + 1);
            final @Nullable String content = contentOrNull(object);
            if (content == null) {
                out.append(object).append(" missing\n");
                continue;
            }
            final int size = content.getBytes(StandardCharsets.UTF_8).length;
            final String format = last.substring(last.indexOf('=') + 1);
            out.append(
                            format.replace("%(objectname)", object)
                                    .replace("%(objecttype)", "blob")
                                    .replace("%(objectsize)", String.valueOf(size))
                                    .replace("%(rest)", rest))
                    .append('\n');
            if (last.startsWith("--batch=")) {
                out.append(content).append('\n');
            }
        }
        return new CommandResult(command, 0, out.toString(), "");
    }

    private @Nullable String contentOrNull(final String object) {
        for (final String[] blob : changed.values()) {
            if (blob[0].equals(object)) {
                return blob[1];
            }
        }
        return null;
    }

    private String content(final String object) {
        final String content = contentOrNull(object);
        return content == null ? "" : content;
    }

    private ReviewRanking.Review review() {
        final CommandRunner runner = this::answer;
        return new GitGate(runner, Path.of("/nonexistent/mirror.git"), GateMode.OFFLINE, null)
                .rankedReview(new GitGate.Waiting("c".repeat(40), "subject"), null, false);
    }

    @Test
    void thePatchIsPutInTheRankingsOrderWithoutAskingGitToOrderIt() {
        change("a.txt", "plain\n");
        change("b.txt", "// @generated by a tool\n");
        change("package-lock.json", "{}\n");

        final ReviewRanking.Review review = review();

        assertThat(review.files())
                .extracting(ReviewRanking.File::path)
                .containsExactly("package-lock.json", "a.txt", "b.txt");
        assertThat(review.patch())
                .as("the patch in the ranking's order, every part whole")
                .isEqualTo(part("package-lock.json") + part("a.txt") + part("b.txt"));
        assertThat(asked)
                .as("no order file is passed to git")
                .allSatisfy(
                        arguments ->
                                assertThat(arguments)
                                        .noneMatch(argument -> argument.startsWith("-O")));
    }

    @Test
    void theHeadsOfTheChangedFilesAreReadByOneProgram() {
        final List<String> generated = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            change("plain-" + i + ".txt", "line " + i + "\n");
        }
        for (int i = 0; i < 5; i++) {
            final String path = "made-" + i + ".txt";
            generated.add(path);
            // The mark past a line that holds what a batch header looks like, as content may.
            change(path, blob(0) + " " + i + "\nCode generated by a tool. DO NOT EDIT.\n");
        }

        final ReviewRanking.Review review = review();

        assertThat(review.files())
                .filteredOn(file -> file.rank() == ReviewRanking.Rank.GENERATED)
                .extracting(ReviewRanking.File::path)
                .containsExactlyInAnyOrderElementsOf(generated);
        assertThat(asked.stream().filter(arguments -> arguments.contains("cat-file")).toList())
                .as("the objects' sizes and then their heads, each read once for all files")
                .hasSize(2)
                .noneMatch(arguments -> arguments.contains("-p"));
    }

    @Test
    void aHeadIsCutToTheSizeOneHeadHas() {
        change("long.txt", "x".repeat(4096) + "@generated\n");
        change("short.txt", "x".repeat(10) + "@generated\n");

        final ReviewRanking.Review review = review();

        assertThat(review.files())
                .extracting(ReviewRanking.File::path, ReviewRanking.File::rank)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "long.txt", ReviewRanking.Rank.ORDINARY),
                        org.assertj.core.groups.Tuple.tuple(
                                "short.txt", ReviewRanking.Rank.GENERATED));
    }
}
