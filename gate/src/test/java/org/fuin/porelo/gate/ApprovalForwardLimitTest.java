package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The push an approval forwards ends at the gate's pass-on limit when the forge never takes it, as
 * the push an online task passes on does.
 */
class ApprovalForwardLimitTest {

    private static final String COMMIT = "a".repeat(40);

    private static final String UPSTREAM = "/nowhere/shop.git";

    @TempDir Path dir;

    /** Every limit a push to the upstream was given; {@code null} for none of its own. */
    private final List<@Nullable Duration> pushLimits = new ArrayList<>();

    private final List<List<String>> asked = new ArrayList<>();

    /** Whether the upstream holds the commit when asked after a push that ended at its limit. */
    private boolean heldAfter;

    /**
     * A forge that takes the connection and never answers: the runner ends the push at the
     * command's own limit, or at a long limit of its own when the command names none.
     */
    private final CommandRunner runner =
            command -> {
                final List<String> arguments = command.arguments();
                asked.add(arguments);
                if (arguments.contains("for-each-ref")) {
                    return done(command, GitGate.INCOMING + "task-1\n");
                }
                if (arguments.contains("rev-parse")) {
                    return done(command, COMMIT + "\n");
                }
                if (arguments.contains("ls-remote")) {
                    final boolean pushed = asked.stream().anyMatch(each -> each.contains("push"));
                    return done(
                            command,
                            pushed && heldAfter ? COMMIT + "\trefs/heads/porelo/task-1\n" : "");
                }
                if (arguments.contains("cat-file")) {
                    return done(command, "tree t\ngpgsig signed\n");
                }
                if (arguments.contains("push") && arguments.contains(UPSTREAM)) {
                    pushLimits.add(command.timeout());
                    throw CommandException.timedOut(
                            command,
                            command.timeout() == null ? Duration.ofHours(1) : command.timeout(),
                            "");
                }
                return done(command, "");
            };

    private static CommandResult done(final Command command, final String output) {
        return new CommandResult(command, 0, output, "");
    }

    private GitGate gate() {
        return new GitGate(runner, dir.resolve("mirror.git"), GateMode.GATEKEEPING, UPSTREAM)
                .passingOnWithin(Duration.ofSeconds(10));
    }

    @Test
    void anApprovalToAForgeThatNeverTakesThePushEndsAtThePassOnLimit() {
        // The forward ran with no limit of its own: a person's approval waited the runner's, far
        // longer than the project's pass_on, before it was told anything.
        assertThatThrownBy(() -> gate().approve("task-1", "porelo/task-1", COMMIT))
                .isInstanceOf(GateException.ForgeSilent.class)
                .hasMessage(
                        "the forge did not answer within 10 s and did not hold the push when"
                                + " asked after - push again");
        assertThat(pushLimits).containsExactly(Duration.ofSeconds(10));
        assertThat(asked)
                .as("nothing is recorded as forwarded or landed")
                .noneMatch(each -> each.contains("update-ref"));
    }

    @Test
    void anApprovalTheStalledForgeHoldsIsForwarded() {
        // Ended at the limit after the forge set the branch: the work is there, and the approval
        // is complete.
        heldAfter = true;

        assertThat(gate().approve("task-1", "porelo/task-1", COMMIT)).isNull();
        assertThat(asked)
                .anyMatch(
                        each ->
                                each.contains("update-ref")
                                        && each.contains(GitGate.LANDED + "task-1/porelo/task-1"));
    }

    @Test
    void aSignedApprovalToAForgeThatNeverTakesThePushEndsAtThePassOnLimit() {
        assertThatThrownBy(
                        () ->
                                gate().approveSigned(
                                                "task-1",
                                                "porelo/task-1",
                                                dir.resolve("merge"),
                                                COMMIT))
                .isInstanceOf(GateException.ForgeSilent.class)
                .hasMessageStartingWith("the forge did not answer within 10 s");
        assertThat(pushLimits).containsExactly(Duration.ofSeconds(10));
    }
}
