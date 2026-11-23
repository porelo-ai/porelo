package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How long one large review takes of git in the gate: a push of many small files with paths of
 * about 140 characters, and a push of fewer files with paths of about 2,855 characters. Every git
 * program the review starts is timed, and everything Review reads must come within {@link #BOUND},
 * which git's own {@code -O} ordering alone missed at these sizes.
 *
 * <p>It prints its table and writes it to {@code target/}. Sizes are taken from system properties,
 * so a smaller run shows how the time grows: {@code review.timing.files}, {@code
 * review.timing.pathLength} and {@code review.timing.componentLength}.
 */
@Tag("slow")
class ReviewTimingTest {

    private static final Duration LIMIT = Duration.ofMinutes(30);

    /** What one review may take of git at these sizes, all its reads together. */
    private static final Duration BOUND = Duration.ofSeconds(3);

    private final ProcessCommandRunner real = new ProcessCommandRunner(LIMIT);

    private final List<Timed> timed = new CopyOnWriteArrayList<>();

    @TempDir private Path root;

    /** One program the gate ran, and how long it took. */
    private record Timed(List<String> arguments, long millis) {}

    @Test
    void manySmallFilesWithShortPaths() throws IOException {
        measure(
                "many-files",
                Integer.getInteger("review.timing.files", 10_000),
                Integer.getInteger("review.timing.pathLength", 140),
                Integer.getInteger("review.timing.componentLength", 30));
    }

    @Test
    void fewerFilesWithVeryLongPaths() throws IOException {
        measure(
                "long-paths",
                Integer.getInteger("review.timing.longFiles", 1_002),
                Integer.getInteger("review.timing.longPathLength", 2_855),
                Integer.getInteger("review.timing.longComponentLength", 100));
    }

    private void measure(
            final String label, final int count, final int pathLength, final int componentLength)
            throws IOException {
        final Path mirror = root.resolve("mirror.git");
        final Path work = root.resolve("work");
        final CommandRunner timing =
                command -> {
                    final long start = System.nanoTime();
                    try {
                        return real.run(command);
                    } finally {
                        timed.add(
                                new Timed(
                                        command.arguments(),
                                        (System.nanoTime() - start) / 1_000_000));
                    }
                };
        final GitGate gate = new GitGate(timing, mirror, GateMode.GATEKEEPING, null);
        gate.initialize();
        git(root, "init", "--initial-branch=main", work.toString());
        for (int i = 0; i < count; i++) {
            final Path file = work.resolve(path(i, pathLength, componentLength));
            Files.createDirectories(file.getParent());
            Files.writeString(file, "line " + i + "\n", StandardCharsets.UTF_8);
        }
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "many files");
        git(work, "push", "-q", mirror.toString(), "HEAD:" + GitGate.INCOMING + "case");
        timed.clear();

        final Map<String, Long> phases = new LinkedHashMap<>();
        final GitGate.Waiting head = gate.pendingWork("case");
        long start = System.nanoTime();
        final ReviewRanking.Review review = gate.rankedReview(head, null, false);
        phases.put("rankedReview", (System.nanoTime() - start) / 1_000_000);
        final List<Timed> ofRanking = List.copyOf(timed);
        start = System.nanoTime();
        gate.blobs(head, review.files().stream().map(ReviewRanking.File::path).toList());
        phases.put("blobs", (System.nanoTime() - start) / 1_000_000);
        start = System.nanoTime();
        gate.log(head, null);
        phases.put("log", (System.nanoTime() - start) / 1_000_000);
        start = System.nanoTime();
        gate.made(head, null);
        phases.put("made", (System.nanoTime() - start) / 1_000_000);

        final StringBuilder table = new StringBuilder();
        table.append("# ")
                .append(label)
                .append(": ")
                .append(count)
                .append(" files, paths of ")
                .append(pathLength)
                .append(" characters in components of ")
                .append(componentLength)
                .append(", patch ")
                .append(review.patch().length())
                .append(" characters\n");
        phases.forEach(
                (phase, millis) ->
                        table.append(String.format("%10d ms  phase %s%n", millis, phase)));
        final Map<String, long[]> byKind = new LinkedHashMap<>();
        for (final Timed one : ofRanking) {
            final long[] sum = byKind.computeIfAbsent(kind(one.arguments()), k -> new long[2]);
            sum[0]++;
            sum[1] += one.millis();
        }
        byKind.forEach(
                (kind, sum) ->
                        table.append(
                                String.format(
                                        "%10d ms  %6d x  in rankedReview: %s%n",
                                        sum[1], sum[0], kind)));
        System.out.print(table);
        final Path report = Path.of("target", "review-timing-" + label + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, table, StandardCharsets.UTF_8);

        assertThat(review.files()).hasSize(count);
        final long total = phases.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total)
                .as("everything Review reads from git, in ms; git's -O alone took 5.6 s here")
                .isLessThan(BOUND.toMillis());
    }

    /** What kind of git program it was: its verb and the options that tell its reads apart. */
    private static String kind(final List<String> arguments) {
        final List<String> shown = new ArrayList<>();
        boolean verb = false;
        for (int i = 1; i < arguments.size(); i++) {
            final String argument = arguments.get(i);
            if ("--git-dir".equals(argument)) {
                i++;
                continue;
            }
            if (argument.startsWith("--output=")) {
                continue;
            }
            if (argument.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                continue;
            }
            verb = true;
            shown.add(argument);
        }
        return verb ? String.join(" ", shown) : String.join(" ", arguments);
    }

    /**
     * A path of about the given length, in components of about the given length, unique by its
     * number and sharing its directories with its neighbours.
     */
    private static String path(final int i, final int length, final int component) {
        final String name = "file-" + i + ".txt";
        final StringBuilder path = new StringBuilder();
        int directory = 0;
        while (path.length() + component + 1 + name.length() <= length) {
            final String part = "d" + directory + "-" + (i / 100) % 7 + "-";
            path.append(part).append("x".repeat(Math.max(1, component - part.length())));
            path.append('/');
            directory++;
        }
        final int pad = Math.max(0, length - path.length() - name.length());
        return path + "p".repeat(Math.min(pad, 200)) + name;
    }

    private CommandResult git(final Path directory, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return real.runOrFail(
                new Command(
                        all,
                        directory,
                        Map.of(
                                "GIT_AUTHOR_NAME",
                                "Test",
                                "GIT_AUTHOR_EMAIL",
                                "test@example.com",
                                "GIT_COMMITTER_NAME",
                                "Test",
                                "GIT_COMMITTER_EMAIL",
                                "test@example.com",
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_TERMINAL_PROMPT",
                                "0"),
                        null));
    }
}
