package org.fuin.porelo.app;

import java.util.HashSet;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.testing.FakeCommandRunner;

/** Models successful stops and removals in the runtime inventory. */
final class StoppingCommandRunner extends FakeCommandRunner {
    private String initialState;
    private String afterStop;
    private boolean attemptedStop;

    void afterStop(String state) {
        afterStop = state;
    }

    private final java.util.Map<String, String> initialStates = new java.util.HashMap<>();

    void initially(String state) {
        initialState = state;
    }

    private final java.util.Map<String, String> names = new java.util.HashMap<>();

    private final Set<String> stopped = new HashSet<>();
    private final Set<String> removed = new HashSet<>();

    private static String id(String row) {
        final String[] fields = row.split("\t", -1);
        return fields.length > 8 ? fields[8] : fields[0];
    }

    @Override
    public FakeCommandRunner answering(String match, String output) {
        if (match.equals("ps")) {
            initialStates.clear();
            output.lines()
                    .forEach(
                            row -> {
                                final String[] fields = row.split("\t", -1);
                                if (fields.length > 1)
                                    initialStates.put(id(row), stateWord(fields[1]));
                            });
        }
        return super.answering(match, output);
    }

    private static String stateWord(String status) {
        final String word = status.toLowerCase(java.util.Locale.ROOT);
        if (word.startsWith("up")) return "running";
        if (word.startsWith("exited")) return "exited";
        return word;
    }

    @Override
    public CommandResult run(Command command) {
        final CommandResult result = super.run(command);
        final String line = command.describe();
        if (line.startsWith("podman stop ")) attemptedStop = true;
        if (result.successful()
                && line.startsWith("podman create ")
                && !result.trimmedOutput().isEmpty()) {
            final String id = result.trimmedOutput();
            initialStates.put(id, "created");
            final int option = command.arguments().indexOf("--name");
            if (option >= 0) names.put(command.arguments().get(option + 1), id);
        }
        if (result.successful() && line.startsWith("podman start ")) {
            final String target = command.arguments().getLast();
            initialStates.put(names.getOrDefault(target, target), "running");
        }
        if (result.exitCode() == 0 && line.startsWith("podman stop ")) {
            stopped.add(command.arguments().getLast());
        }
        if (result.exitCode() == 0 && line.startsWith("podman rm ")) {
            removed.add(command.arguments().getLast());
        }
        if (line.contains("{{.State.Status}}")
                && result.successful()
                && result.trimmedOutput().isEmpty()) {
            final String id = command.arguments().getLast();
            final String state =
                    attemptedStop && afterStop != null
                            ? afterStop
                            : stopped.contains(id)
                                    ? "exited"
                                    : initialState != null ? initialState : initialStates.get(id);
            return removed.contains(id) || state == null
                    ? new CommandResult(command, 125, "", "Error: no such container " + id)
                    : new CommandResult(command, 0, state, "");
        }
        if (line.startsWith("podman ps ")) {
            final String output =
                    result.standardOutput()
                            .lines()
                            .filter(row -> !removed.contains(id(row)))
                            .map(
                                    row ->
                                            stopped.contains(id(row))
                                                    ? row.replaceFirst("\tUp[^\t]*", "\tExited (0)")
                                                    : row)
                            .map(
                                    row -> {
                                        if (!line.contains("{{.ID}}\t{{.State}}")) return row;
                                        final String[] fields = row.split("\t", -1);
                                        if (fields.length < 3) return row;
                                        return id(row) + "\t" + stateWord(fields[1]);
                                    })
                            .collect(java.util.stream.Collectors.joining("\n"));
            return new CommandResult(command, result.exitCode(), output, result.standardError());
        }
        return result;
    }
}
