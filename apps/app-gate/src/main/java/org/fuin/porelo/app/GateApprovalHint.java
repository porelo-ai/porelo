package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.fuin.porelo.gate.GitGate;
import org.jspecify.annotations.Nullable;

/** The approval command for the revision shown, with its destination kept intact. */
record GateApprovalHint(
        String project,
        String repository,
        String name,
        String commit,
        @Nullable String upstream,
        @Nullable String branch,
        boolean signed) {

    /**
     * Returns the line a review or a checkout ends with: the approval command bound to the commit
     * shown, or why there is none - a command that would only be refused is never offered.
     *
     * @param gate The gate the work waits at.
     * @param now The commit the push holds now.
     * @param hint The approval of the commit shown.
     * @return The command, safe to print, or the reason in its place.
     */
    static String line(final GitGate gate, final String now, final GateApprovalHint hint) {
        final String refused = gate.forwardRefusal();
        if (refused != null) {
            return "none from here: " + TalkReadCommand.shown(refused);
        }
        if (!now.equals(hint.commit())) {
            return "none for this commit: "
                    + TalkReadCommand.shown(hint.name())
                    + " holds "
                    + now
                    + " now; review that one before approving";
        }
        return TalkReadCommand.shown(hint.command());
    }

    String command() {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "porelo",
                                "gate",
                                "approve",
                                "--project",
                                project,
                                "--repository",
                                repository,
                                "--commit",
                                commit));
        if (upstream != null) arguments.addAll(List.of("--upstream", upstream));
        if (branch != null) arguments.addAll(List.of("--branch", branch));
        if (signed) arguments.add("--signed");
        arguments.addAll(List.of("--", name));
        return arguments.stream().map(GateApprovalHint::quoted).collect(Collectors.joining(" "));
    }

    static String quoted(String argument) {
        return argument.matches("[a-zA-Z0-9_./:@%+=,-]+")
                ? argument
                : "'" + argument.replace("'", "'\"'\"'") + "'";
    }
}
