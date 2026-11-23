package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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

    private static String quoted(String argument) {
        return argument.matches("[a-zA-Z0-9_./:@%+=,-]+")
                ? argument
                : "'" + argument.replace("'", "'\"'\"'") + "'";
    }
}
