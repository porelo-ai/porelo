package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's {@code limits.pass_on} bounds the push a person's approval forwards, through the gate
 * every approval is made with: {@code porelo gate approve}, the daemon's Approve and a checkout's.
 */
class GateApprovalPassOnTest {

    private static final String COMMIT = "a".repeat(40);

    private static final String UPSTREAM = "/nowhere/shop.git";

    @TempDir Path dir;

    /** Every limit a push to the upstream was given; {@code null} for none of its own. */
    private final List<@Nullable Duration> pushLimits = new ArrayList<>();

    /**
     * A forge that never takes the push: the runner ends it at the command's own limit, or at a
     * long one of its own when the command names none.
     */
    private final CommandRunner runner =
            command -> {
                final List<String> arguments = command.arguments();
                if (arguments.contains("for-each-ref")) {
                    return new CommandResult(command, 0, GitGate.INCOMING + "task-1\n", "");
                }
                if (arguments.contains("rev-parse")) {
                    return new CommandResult(command, 0, COMMIT + "\n", "");
                }
                if (arguments.contains("push") && arguments.contains(UPSTREAM)) {
                    final Duration own = command.timeout();
                    pushLimits.add(own);
                    throw CommandException.timedOut(
                            command, own == null ? Duration.ofHours(1) : own, "");
                }
                return new CommandResult(command, 0, "", "");
            };

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                arguments -> 0,
                name -> null);
    }

    private static Project project(final String limits) {
        return ProjectReader.read(
                new StringReader(
                        """
                        project:
                          name: "p"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        repositories:
                          shop:
                            upstream: "%s"
                        """
                                        .formatted(UPSTREAM)
                                + limits),
                "project.yml");
    }

    @Test
    void anApprovalWaitsForTheForgeAsLongAsTheProjectsPassOnSays() {
        // The gate an approval is made with never heard of the project's pass_on: it waited the
        // default, or whatever the runner allows, for a forge that never took the push.
        final Project project = project("limits:\n  pass_on: \"10s\"\n");
        final GitGate gate =
                GateSupport.gate(context(), project, project.repository("shop"), null, null);

        assertThatThrownBy(() -> gate.approve("task-1", "porelo/task-1", COMMIT))
                .isInstanceOf(GateException.ForgeSilent.class)
                .hasMessageStartingWith("the forge did not answer within 10 s");
        assertThat(pushLimits).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void aProjectThatSetsNoPassOnWaitsTwoMinutes() {
        final Project project = project("");
        final GitGate gate =
                GateSupport.gate(context(), project, project.repository("shop"), null, null);

        assertThatThrownBy(() -> gate.approve("task-1", "porelo/task-1", COMMIT))
                .hasMessageStartingWith("the forge did not answer within 120 s");
        assertThat(pushLimits).containsExactly(Duration.ofMinutes(2));
    }
}
