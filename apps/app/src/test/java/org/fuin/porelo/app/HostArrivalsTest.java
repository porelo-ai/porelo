package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostArrivalsTest {
    @TempDir Path directory;

    @Test
    void theHostReceivesEachVerdictBeforeTheNextCommitIsRead() throws Exception {
        final var output = new java.io.StringWriter();
        final var errors = new java.io.StringWriter();
        final AtomicInteger read = new AtomicInteger();
        final CommandRunner runner =
                command -> {
                    final var args = command.arguments();
                    String text = "";
                    if (args.contains("--show-object-format")) text = "sha1\n";
                    else if (args.contains("rev-list"))
                        text = "a".repeat(40) + "\n" + "b".repeat(40) + "\n";
                    else if (args.contains("cat-file")) {
                        if (read.getAndIncrement() > 0)
                            assertThat(output.toString().lines())
                                    .as("the previous verdict is already flushed")
                                    .hasSize(1);
                        text = "tree " + "c".repeat(40) + "\n\nunsigned\n";
                    }
                    return new CommandResult(command, 0, text, "");
                };
        final Path signers = directory.resolve("signers");
        Files.writeString(signers, "");
        final var context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                org.fuin.porelo.core.config.XdgPaths.of(name -> null, directory),
                                directory.resolve("bin")),
                        args -> 0,
                        name -> name.equals("TMPDIR") ? directory.toString() : null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(
                                new java.io.ByteArrayInputStream(
                                        ("0".repeat(40)
                                                        + " "
                                                        + "b".repeat(40)
                                                        + " refs/heads/main\n")
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets
                                                                .US_ASCII)),
                                false));
        final var command = new CommitVerifyCommand();
        command.setContext(context);
        final var cli = new picocli.CommandLine(command);
        cli.setOut(new java.io.PrintWriter(output));
        cli.setErr(new java.io.PrintWriter(errors));
        assertThat(
                        cli.execute(
                                "--repository",
                                directory.toString(),
                                "--signers",
                                signers.toString(),
                                "--certifiers",
                                signers.toString(),
                                "--json-lines"))
                .as(errors.toString())
                .isEqualTo(65);
        assertThat(read.get()).isEqualTo(2);
        assertThat(output.toString().lines()).hasSize(3);
        final var summary =
                (java.util.Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                output.toString().lines().toList().getLast());
        assertThat(summary).isNotNull();
        assertThat(summary.get("count")).isEqualTo(2.0);
        assertThat(summary.get("accepted")).isEqualTo(false);
    }

    @Test
    void aCommitWalkLargerThanTheRunnersEightMebibytesIsJudgedCompletely() throws Exception {
        final int count = 210000;
        assertThat((long) count * 41).isGreaterThan(8 * 1024 * 1024);
        final AtomicInteger judged = new AtomicInteger();
        final CommandRunner runner =
                command -> {
                    final var args = command.arguments();
                    String output = "";
                    if (args.contains("--show-object-format")) output = "sha1\n";
                    else if (args.contains("rev-list")) {
                        final int maximum =
                                args.stream()
                                        .filter(a -> a.startsWith("--max-count="))
                                        .mapToInt(a -> Integer.parseInt(a.substring(12)))
                                        .findFirst()
                                        .orElse(count);
                        final int skip =
                                args.stream()
                                        .filter(a -> a.startsWith("--skip="))
                                        .mapToInt(a -> Integer.parseInt(a.substring(7)))
                                        .findFirst()
                                        .orElse(0);
                        final StringBuilder lines = new StringBuilder();
                        for (int index = skip + 1;
                                index <= Math.min(count, skip + maximum);
                                index++) lines.append("%040x\n".formatted(index));
                        output = lines.toString();
                        // Reproduce ProcessCommandRunner's tail retention, including its cut
                        // marker.
                        if (output.length() > 8 * 1024 * 1024)
                            output =
                                    "[output truncated]\n"
                                            + output.substring(output.length() - 8 * 1024 * 1024);
                    } else if (args.contains("cat-file")) {
                        judged.incrementAndGet();
                        output = "tree " + "a".repeat(40) + "\n\nunsigned\n";
                    }
                    return new CommandResult(command, 0, output, "");
                };
        final Path signers = directory.resolve("signers");
        Files.writeString(signers, "");
        final var arrivals =
                HostArrivals.reading(
                        runner,
                        directory,
                        signers,
                        signers,
                        null,
                        null,
                        name -> name.equals("TMPDIR") ? directory.toString() : null,
                        Clock.systemUTC());
        final var answer =
                arrivals.judge(
                        ("0".repeat(40) + " " + "b".repeat(40) + " refs/heads/main\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(judged.get())
                .as("the complete walk, including its oldest commits, is judged")
                .isEqualTo(count);
        assertThat(answer.commits()).hasSize(count);
        assertThat(answer.commits().getLast().commit()).isEqualTo("%040x".formatted(count));
    }

    @Test
    void eachCommitsScratchFilesGoBeforeTheNextCommitIsRead() throws Exception {
        // A push of any size must leave no more scratch files than one commit writes.
        final String signed = signedCommit();
        final List<Long> left = new java.util.ArrayList<>();
        final CommandRunner runner =
                command -> {
                    final var args = command.arguments();
                    String text = "";
                    if (args.contains("--show-object-format")) text = "sha1\n";
                    else if (args.contains("rev-list"))
                        text =
                                "a".repeat(40)
                                        + "\n"
                                        + "b".repeat(40)
                                        + "\n"
                                        + "c".repeat(40)
                                        + "\n";
                    else if (args.contains("cat-file")) {
                        left.add(scratchFiles("commit-"));
                        text = signed;
                    }
                    return new CommandResult(command, 0, text, "");
                };
        final var answer = arrivals(runner).judge(update());
        assertThat(answer.commits())
                .as("each commit is judged, and refused as signed by no signer in force")
                .hasSize(3)
                .allMatch(each -> each.reason().startsWith("signed by "));
        assertThat(left).as("no earlier commit's signature file is left").containsOnly(0L);
    }

    @Test
    void aStreamedJudgementNamesOnlyTheFirstHundredRefusedCommits() throws Exception {
        // porelo-host keeps at most 1 MiB of the worker's standard error, whatever the push.
        final CommandRunner runner =
                command -> {
                    final var args = command.arguments();
                    String text = "";
                    if (args.contains("--show-object-format")) text = "sha1\n";
                    else if (args.contains("rev-list")) {
                        final StringBuilder lines = new StringBuilder();
                        for (int index = 1; index <= 150; index++)
                            lines.append("%040x\n".formatted(index));
                        text = lines.toString();
                    } else if (args.contains("cat-file"))
                        text = "tree " + "c".repeat(40) + "\n\nunsigned\n";
                    return new CommandResult(command, 0, text, "");
                };
        final var output = new java.io.StringWriter();
        final var errors = new java.io.StringWriter();
        assertThat(verify(runner, output, errors, "--json-lines")).isEqualTo(65);
        assertThat(errors.toString().lines())
                .hasSize(CommitVerifyCommand.NAMED_REFUSALS + 1)
                .endsWith("porelo: 50 more commits are refused");
        assertThat(output.toString().lines()).as("every verdict is still answered").hasSize(151);
    }

    /**
     * A ref that lands between two windows - the task's own earlier push, accepted meanwhile - must
     * not shift the later windows over commits of this push that no ref holds: those would never be
     * judged while the push still reads as judged whole.
     */
    @Test
    void aRefLandingBetweenTwoWindowsNeverHidesACommitOfThePush() throws Exception {
        final Graph graph = new Graph();
        // A main line and a side line whose dates interleave, merged at the pushed tip.
        for (int index = 1; index <= 6000; index++)
            graph.commit(
                    "m" + index, 2L * index, index == 1 ? List.of() : List.of("m" + (index - 1)));
        for (int index = 1; index <= 3000; index++)
            graph.commit(
                    "s" + index,
                    2L * index + 1,
                    index == 1 ? List.of() : List.of("s" + (index - 1)));
        graph.commit("tip", 100_000, List.of("m6000", "s3000"));
        graph.afterFirstWindow = () -> graph.refs.put("refs/heads/porelo/earlier", "s3000");
        final var answer = arrivals(graph.runner()).judge(graph.update("tip", "refs/heads/main"));
        assertThat(graph.judged)
                .as("every commit of the push is judged, and each once")
                .hasSize(9001)
                .doesNotHaveDuplicates();
        assertThat(answer.commits()).hasSize(9001);
        assertThat(answer.reason()).isEmpty();
    }

    @Test
    void anExactlyFullLastWindowEndsTheWalkWithEveryCommitJudgedOnce() throws Exception {
        final Graph graph = Graph.line("c", 2 * 4096);
        final var answer =
                arrivals(graph.runner()).judge(graph.update("c" + 2 * 4096, "refs/heads/main"));
        assertThat(graph.judged).hasSize(2 * 4096).doesNotHaveDuplicates();
        assertThat(answer.commits()).hasSize(2 * 4096);
    }

    @Test
    void twoOverlappingRefsOfOnePushJudgeTheirSharedCommitsOnceForTheFirst() throws Exception {
        final Graph graph = Graph.line("c", 5000);
        graph.commit("b1", 10_001, List.of("c3000"));
        graph.commit("b2", 10_002, List.of("b1"));
        graph.refs.put("refs/heads/base", "c1000");
        final byte[] input =
                (new String(graph.update("c5000", "refs/heads/one"), StandardCharsets.US_ASCII)
                                + new String(
                                        graph.update("b2", "refs/heads/two"),
                                        StandardCharsets.US_ASCII))
                        .getBytes(StandardCharsets.US_ASCII);
        final var answer = arrivals(graph.runner()).judge(input);
        assertThat(graph.judged)
                .as("what a ref already holds is not judged, what both refs bring only once")
                .hasSize(4000 + 2)
                .doesNotHaveDuplicates()
                .doesNotContain(Graph.id("c1000"), Graph.id("c1"));
        assertThat(answer.commits())
                .filteredOn(each -> each.ref().equals("refs/heads/two"))
                .extracting(HostArrivals.Verdict::commit)
                .containsExactlyInAnyOrder(Graph.id("b1"), Graph.id("b2"));
    }

    @Test
    void refsThatCannotBeReadWholeRefuseThePushBeforeAnyCommitIsJudged() throws Exception {
        for (final String said :
                List.of("[4096 bytes cut before this]\n" + "a".repeat(40) + "\n", "not an id\n")) {
            final Graph graph = Graph.line("c", 3);
            graph.refsAnswer = said;
            final var answer = arrivals(graph.runner()).judge(graph.update("c3", "refs/heads/m"));
            assertThat(answer.reason()).as(said).contains("refs");
            assertThat(answer.commits()).isEmpty();
            assertThat(graph.judged).isEmpty();
        }
        final Graph failing = Graph.line("c", 3);
        failing.refsExit = 128;
        final var answer = arrivals(failing.runner()).judge(failing.update("c3", "refs/heads/m"));
        assertThat(answer.reason()).contains("refs");
        assertThat(failing.judged).isEmpty();
    }

    /**
     * A repository's commit graph as git walks it: {@code rev-list} honors every included and
     * excluded tip, on its command line or its standard input, {@code --all} reads the refs as they
     * are at that moment, and {@code --skip}/{@code --max-count} page the newest-first order.
     */
    static final class Graph {
        final Map<String, List<String>> parents = new java.util.HashMap<>();
        final Map<String, Long> dates = new java.util.HashMap<>();
        final Map<String, String> refs = new java.util.LinkedHashMap<>();
        final List<String> judged = new java.util.ArrayList<>();
        Runnable afterFirstWindow = () -> {};
        String refsAnswer;
        int refsExit;
        private int windows;

        static String id(String name) {
            return "%040x"
                    .formatted(new java.math.BigInteger(1, name.getBytes(StandardCharsets.UTF_8)));
        }

        static Graph line(String prefix, int count) {
            final Graph graph = new Graph();
            for (int index = 1; index <= count; index++)
                graph.commit(
                        prefix + index,
                        index,
                        index == 1 ? List.of() : List.of(prefix + (index - 1)));
            return graph;
        }

        void commit(String name, long date, List<String> parentNames) {
            parents.put(id(name), parentNames.stream().map(Graph::id).toList());
            dates.put(id(name), date);
        }

        byte[] update(String tip, String ref) {
            return ("0".repeat(40) + " " + id(tip) + " " + ref + "\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        private java.util.Set<String> reachable(java.util.Collection<String> tips) {
            final java.util.Set<String> seen = new java.util.HashSet<>();
            final java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>(tips);
            while (!open.isEmpty()) {
                final String each = open.pop();
                if (seen.add(each)) open.addAll(parents.getOrDefault(each, List.of()));
            }
            return seen;
        }

        CommandRunner runner() {
            return command -> {
                final List<String> args = command.arguments();
                String text = "";
                int exit = 0;
                if (args.contains("--show-object-format")) text = "sha1\n";
                else if (args.contains("for-each-ref")) {
                    exit = refsExit;
                    text =
                            refsAnswer != null
                                    ? refsAnswer
                                    : String.join(
                                            "",
                                            refs.values().stream()
                                                    .map(name -> id(name) + "\n")
                                                    .toList());
                } else if (args.contains("rev-list")) {
                    text = walk(args, command.input());
                    if (windows++ == 0) afterFirstWindow.run();
                } else if (args.contains("cat-file")) {
                    judged.add(args.getLast());
                    text = "tree " + "c".repeat(40) + "\n\nunsigned\n";
                }
                return new CommandResult(command, exit, text, "");
            };
        }

        private String walk(List<String> args, String input) {
            final List<String> included = new java.util.ArrayList<>();
            final List<String> excluded = new java.util.ArrayList<>();
            int maximum = Integer.MAX_VALUE;
            int skip = 0;
            boolean not = false;
            final List<String> tokens =
                    new java.util.ArrayList<>(
                            args.subList(args.indexOf("rev-list") + 1, args.size()));
            for (int index = 0; index < tokens.size(); index++) {
                final String each = tokens.get(index);
                if (each.startsWith("--max-count=")) maximum = Integer.parseInt(each.substring(12));
                else if (each.startsWith("--skip=")) skip = Integer.parseInt(each.substring(7));
                else if (each.equals("--not")) not = !not;
                else if (each.equals("--end-of-options")) continue;
                else if (each.equals("--all"))
                    (not ? excluded : included)
                            .addAll(refs.values().stream().map(Graph::id).toList());
                else if (each.equals("--stdin"))
                    tokens.addAll(
                            java.util.Objects.requireNonNull(input, "--stdin reads its input")
                                    .lines()
                                    .filter(line -> !line.isBlank())
                                    .toList());
                else if (each.startsWith("^")) excluded.add(each.substring(1));
                else if (each.matches("[0-9a-f]{40}")) (not ? excluded : included).add(each);
                else throw new AssertionError("rev-list is given " + each);
            }
            final java.util.Set<String> hidden = reachable(excluded);
            return reachable(included).stream()
                    .filter(each -> !hidden.contains(each))
                    .sorted(
                            java.util.Comparator.comparing((String each) -> dates.get(each))
                                    .reversed()
                                    .thenComparing(java.util.Comparator.reverseOrder()))
                    .skip(skip)
                    .limit(maximum)
                    .map(each -> each + "\n")
                    .collect(java.util.stream.Collectors.joining());
        }
    }

    @Test
    void jsonAndJsonLinesExcludeEachOther() throws Exception {
        final var errors = new java.io.StringWriter();
        assertThat(
                        verify(
                                command -> {
                                    throw new AssertionError("nothing is run");
                                },
                                new java.io.StringWriter(),
                                errors,
                                "--json",
                                "--json-lines"))
                .isEqualTo(64);
        assertThat(errors.toString()).contains("--json and --json-lines exclude each other");
    }

    private long scratchFiles(String prefix) {
        try (var files = Files.walk(directory)) {
            return files.filter(each -> each.getFileName().toString().startsWith(prefix)).count();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static byte[] update() {
        return ("0".repeat(40) + " " + "b".repeat(40) + " refs/heads/main\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private HostArrivals arrivals(CommandRunner runner) throws Exception {
        final Path signers = directory.resolve("signers");
        Files.writeString(signers, "");
        return HostArrivals.reading(
                runner,
                directory,
                signers,
                signers,
                null,
                null,
                name -> name.equals("TMPDIR") ? directory.toString() : null,
                Clock.systemUTC());
    }

    private int verify(
            CommandRunner runner,
            java.io.StringWriter output,
            java.io.StringWriter errors,
            String... flags)
            throws Exception {
        final Path signers = directory.resolve("signers");
        Files.writeString(signers, "");
        final var context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                org.fuin.porelo.core.config.XdgPaths.of(name -> null, directory),
                                directory.resolve("bin")),
                        args -> 0,
                        name -> name.equals("TMPDIR") ? directory.toString() : null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(new java.io.ByteArrayInputStream(update()), false));
        final var command = new CommitVerifyCommand();
        command.setContext(context);
        final var cli = new picocli.CommandLine(command);
        cli.setOut(new java.io.PrintWriter(output));
        cli.setErr(new java.io.PrintWriter(errors));
        final List<String> arguments =
                new java.util.ArrayList<>(
                        List.of(
                                "--repository",
                                directory.toString(),
                                "--signers",
                                signers.toString(),
                                "--certifiers",
                                signers.toString()));
        arguments.addAll(List.of(flags));
        return cli.execute(arguments.toArray(String[]::new));
    }

    /** A commit carrying a readable SSH signature by an Ed25519 key no signer file names. */
    private static String signedCommit() throws Exception {
        final var key = new java.io.ByteArrayOutputStream();
        sshString(key, "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        sshString(key, new byte[32]);
        final var blob = new java.io.ByteArrayOutputStream();
        blob.write("SSHSIG".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        blob.write(new byte[] {0, 0, 0, 1});
        sshString(blob, key.toByteArray());
        sshString(blob, "git".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        sshString(blob, new byte[0]);
        sshString(blob, "sha512".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        sshString(blob, new byte[83]);
        final String base64 = java.util.Base64.getEncoder().encodeToString(blob.toByteArray());
        final StringBuilder header = new StringBuilder("gpgsig -----BEGIN SSH SIGNATURE-----\n");
        for (int start = 0; start < base64.length(); start += 70)
            header.append(' ')
                    .append(base64, start, Math.min(start + 70, base64.length()))
                    .append('\n');
        header.append(" -----END SSH SIGNATURE-----\n");
        return "tree "
                + "c".repeat(40)
                + "\nauthor A <a@example.org> 1700000000 +0000\ncommitter A <a@example.org>"
                + " 1700000000 +0000\n"
                + header
                + "\nsigned\n";
    }

    private static void sshString(java.io.ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
