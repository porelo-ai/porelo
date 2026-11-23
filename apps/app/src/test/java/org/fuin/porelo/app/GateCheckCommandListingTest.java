package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for how {@link GateCheckCommand} reads the commits a push sends, from a listing larger than
 * a runner keeps of a result: a runner here answers as the process runner does, keeping the end of
 * the listing behind a line saying how much went, and hands the whole of it to a caller that
 * streams it.
 */
class GateCheckCommandListingTest {

    private static final String AGENT = "agent@porelo.invalid";

    /** What the process runner keeps of a result's standard output once it passes twice that. */
    private static final int KEPT = 8 * 1024 * 1024;

    /**
     * A runner whose {@code rev-list} names the given lines and whose {@code show} is a person's.
     */
    private static final class Listing implements CommandRunner {

        private final String listing;

        private final String agentCommit;

        private final List<String> shown = new ArrayList<>();

        private Listing(final String listing, final String agentCommit) {
            this.listing = listing;
            this.agentCommit = agentCommit;
        }

        @Override
        public CommandResult run(final Command command) {
            final List<String> arguments = command.arguments();
            if (arguments.contains("rev-list")) {
                return kept(command, listing);
            }
            final String commit = arguments.getLast();
            shown.add(commit);
            final String author = commit.equals(agentCommit) ? AGENT : "person@example.com";
            return new CommandResult(command, 0, author + "|subject of " + commit + "\n", "");
        }

        @Override
        public CommandResult streamOutput(final Command command, final Consumer<String> lines) {
            if (!command.arguments().contains("rev-list")) {
                return CommandRunner.super.streamOutput(command, lines);
            }
            listing.lines().forEach(lines);
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

    private static String hookLine(final String local, final String remote) {
        return "refs/heads/main " + local + " refs/heads/main " + remote;
    }

    @Test
    void aFirstPushListingMoreThanARunnerKeepsChecksEveryCommitItsOldestIncluded() {
        // 450 000 ids of 41 bytes each, newest first: 18.5 MB, past the 16 MiB at which a result
        // keeps only its last 8 MiB. Read whole through the result, the oldest commits were the
        // ones gone, and the agent's is the oldest.
        final int count = 450_000;
        final StringBuilder listing = new StringBuilder();
        for (int index = count; index >= 1; index--) {
            listing.append(id(index)).append('\n');
        }
        final String oldest = id(1);
        final Listing runner = new Listing(listing.toString(), oldest);

        final var found =
                GateCheckCommand.offenders(
                        runner,
                        null,
                        List.of(hookLine(id(count), GateCheckCommand.ABSENT)),
                        Set.of(AGENT),
                        "origin");

        assertThat(runner.shown.size()).isEqualTo(count);
        assertThat(runner.shown.getLast()).isEqualTo(oldest);
        assertThat(found).extracting(GateCheckCommand.Offender::author).containsExactly(AGENT);
        assertThat(found.getFirst().subject()).isEqualTo("subject of " + oldest);
    }

    @Test
    void aListingLineThatIsNoCommitIdRefusesTheCheck() {
        // The marker a cut leaves, or any other line: taken as a commit it is "not shown" and
        // skipped, and what it stood for goes unchecked.
        for (final String line :
                List.of(
                        "[8600000 bytes cut before this]",
                        "",
                        id(1).substring(1),
                        id(0xabc).toUpperCase(java.util.Locale.ROOT),
                        id(1) + " extra")) {
            final Listing runner = new Listing(id(2) + "\n" + line + "\n" + id(3) + "\n", id(3));

            assertThatThrownBy(
                            () ->
                                    GateCheckCommand.offenders(
                                            runner,
                                            null,
                                            List.of(hookLine(id(2), GateCheckCommand.ABSENT)),
                                            Set.of(AGENT),
                                            "origin"))
                    .as("'%s'", line)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a commit id");
        }
    }

    @Test
    void aSha256ListingIsReadAsCommits() {
        final String commit = "%064x".formatted(7);
        final Listing runner = new Listing(commit + "\n", commit);

        final var found =
                GateCheckCommand.offenders(
                        runner,
                        null,
                        List.of(hookLine(commit, GateCheckCommand.ABSENT)),
                        Set.of(AGENT),
                        "origin");

        assertThat(found).extracting(GateCheckCommand.Offender::author).containsExactly(AGENT);
    }
}
