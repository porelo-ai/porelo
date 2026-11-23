package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how {@link TreeRepository} reads an organization's history longer than a runner keeps
 * of a result: a runner here answers as the process runner does, keeping the end of an output past
 * 16 MiB behind a line saying how much went, and hands the whole of it to a caller that streams it.
 */
class TreeRepositoryHistoryTest {

    /** What the process runner keeps of a result's standard output once it passes twice that. */
    private static final int KEPT = 8 * 1024 * 1024;

    /** 450 000 commits: 18.5 MB of ids alone, past the 16 MiB at which a result is cut. */
    private static final int COUNT = 450_000;

    @TempDir Path dir;

    /** Answers git from what the test says, the first argument it knows deciding. */
    private static final class Answering implements CommandRunner {

        private final Function<List<String>, String> answer;

        private final List<List<String>> asked = new java.util.ArrayList<>();

        private Answering(final Function<List<String>, String> answer) {
            this.answer = answer;
        }

        @Override
        public CommandResult run(final Command command) {
            asked.add(command.arguments());
            final String output = answer.apply(command.arguments());
            return output == null ? new CommandResult(command, 1, "", "no") : kept(command, output);
        }

        @Override
        public CommandResult streamOutput(final Command command, final Consumer<String> lines) {
            asked.add(command.arguments());
            final String output = answer.apply(command.arguments());
            if (output == null) {
                return new CommandResult(command, 1, "", "no");
            }
            output.lines().forEach(lines);
            return new CommandResult(command, 0, "", "");
        }

        /** The end of an output, as the process runner keeps it. */
        private static CommandResult kept(final Command command, final String output) {
            final byte[] all = output.getBytes(StandardCharsets.UTF_8);
            if (all.length <= 2 * KEPT) {
                return new CommandResult(command, 0, output, "");
            }
            int from = all.length - KEPT;
            if (all[from - 1] != '\n') {
                while (all[from] != '\n') {
                    from++;
                }
                from++;
            }
            return new CommandResult(
                    command,
                    0,
                    "["
                            + from
                            + " bytes cut before this]\n"
                            + new String(all, from, all.length - from, StandardCharsets.UTF_8),
                    "",
                    true);
        }
    }

    private static String id(final int index) {
        return "%040x".formatted(index);
    }

    /** Every commit, newest first, each line made from its index. */
    private static String newestFirst(final Function<Integer, String> line) {
        final StringBuilder all = new StringBuilder();
        for (int index = COUNT; index >= 1; index--) {
            all.append(line.apply(index)).append('\n');
        }
        return all.toString();
    }

    private static boolean asks(final List<String> arguments, final String what) {
        return arguments.contains(what);
    }

    private TreeRepository repository(final Answering runner) throws Exception {
        return TreeRepository.standing(dir.resolve("organization.git"), runner);
    }

    @Test
    void anAncestorAsOldAsTheFoundingCommitIsFound() throws Exception {
        final Answering runner =
                new Answering(
                        arguments -> asks(arguments, "rev-list") ? newestFirst(i -> id(i)) : null);

        try (TreeRepository repository = repository(runner)) {
            assertThat(repository.hasAncestor(id(COUNT), id(1))).isTrue();
        }
    }

    @Test
    void whatOnlyThisBranchHoldsIsListedToItsOldest() throws Exception {
        final Answering runner =
                new Answering(
                        arguments ->
                                asks(arguments, "cat-file")
                                        ? ""
                                        : asks(arguments, "rev-list")
                                                ? newestFirst(i -> id(i))
                                                : null);

        try (TreeRepository repository = repository(runner)) {
            final List<String> only = repository.onlyHere(id(0)).orElseThrow();

            assertThat(only).hasSize(COUNT);
            assertThat(only.getLast()).isEqualTo(id(1));
        }
    }

    @Test
    void theOrganizationsLineStartsAtItsFoundingCommit() throws Exception {
        final StringBuilder oldestFirst = new StringBuilder(id(1)).append('\n');
        for (int index = 2; index <= COUNT; index++) {
            oldestFirst.append(id(index)).append(' ').append(id(index - 1)).append('\n');
        }
        final Answering runner =
                new Answering(
                        arguments ->
                                asks(arguments, "--is-shallow-repository")
                                        ? "false\n"
                                        : asks(arguments, "rev-list")
                                                ? oldestFirst.toString()
                                                : null);

        try (TreeRepository repository = repository(runner)) {
            final List<Map.Entry<String, String>> line = repository.lineAfter("main", null);

            assertThat(line).hasSize(COUNT);
            assertThat(line.getFirst()).isEqualTo(Map.entry(id(1), ""));
            assertThat(line.getLast()).isEqualTo(Map.entry(id(COUNT), id(COUNT - 1)));
        }
    }

    @Test
    void aFileSignedAnewByTheOldestCommitIsSeen() throws Exception {
        final Answering runner =
                new Answering(
                        arguments ->
                                asks(arguments, "log")
                                        ? newestFirst(
                                                i -> id(i) + " " + (i == 1 ? "owners.yml" : "x"))
                                        : null);

        try (TreeRepository repository = repository(runner)) {
            assertThat(repository.signingAnew("main", "owners.yml")).containsExactly(id(1));
        }
    }

    @Test
    void aChangeNamedOnlyByTheOldestCommitIsJudged() throws Exception {
        // Its signature is read next, which shows the oldest row was reached rather than lost with
        // the start of the output; no commit of this runner is signed, so none is the change.
        final Answering runner =
                new Answering(
                        arguments ->
                                asks(arguments, "log")
                                        ? newestFirst(i -> id(i) + " " + (i == 1 ? "change-1" : ""))
                                        : null);

        try (TreeRepository repository = repository(runner)) {
            assertThat(repository.changeNamed("change-1", "SHA256:x")).isEmpty();
        }
        assertThat(runner.asked)
                .anyMatch(arguments -> arguments.contains("cat-file") && arguments.contains(id(1)));
    }

    @Test
    void aListedLineThatIsNoCommitIsRefusedNotRead() throws Exception {
        for (final String line :
                List.of("[8600000 bytes cut before this]", "", id(1).substring(2), id(1) + " x")) {
            final Answering runner =
                    new Answering(
                            arguments ->
                                    asks(arguments, "rev-list")
                                            ? id(3) + "\n" + line + "\n" + id(2) + "\n"
                                            : null);

            try (TreeRepository repository = repository(runner)) {
                assertThatThrownBy(() -> repository.hasAncestor(id(3), id(2)))
                        .as("'%s'", line)
                        .isInstanceOfSatisfying(
                                OrganizationQuestions.Refused.class,
                                refused ->
                                        assertThat(refused.reason())
                                                .isEqualTo(
                                                        OrganizationQuestions.Refused.CANNOT_SAY));
            }
        }
    }
}
