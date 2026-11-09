package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.RootOwnedFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Reads what this host's follow last published for a project's central repositories in {@code
 * /etc/porelo-host/organization/central/<project>.json}.
 *
 * <p>Trusted only when the file and every parent directory are root's alone with no symlink; a file
 * that is missing, refused or unparsable answers {@code cannot-say} with {@link HostCentral}'s
 * {@code why} words, never {@code up-to-date}.
 */
@Command(
        name = "central-state",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Reads what this host's follow last found of how a project's copies stand against"
                        + " central.")
public class HostCentralStateCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "<project>",
            description = "Project whose central state to read.")
    private String project;

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

    @Spec private CommandSpec spec;

    /** Where the host publishes central-state files; another directory only in a test. */
    Path directory = HostCentral.DIRECTORY;

    /** Reads file ownership and mode; another reader only in a test. */
    RootOwnedFile.Attributes attributes = HostCentral.ATTRIBUTES;

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!HostCentral.validProject(project)) {
            err.println("porelo: central-state names one project");
            err.flush();
            return 64;
        }
        final Map<String, Object> answer =
                new HostCentral(directory, attributes).centralState(project);
        if (json) {
            out.println(Json.write(answer));
            out.flush();
            return 0;
        }
        if ("cannot-say".equals(answer.get("state"))) {
            out.println("cannot-say  " + String.valueOf(answer.get("why")));
            out.flush();
            return 0;
        }
        final List<?> repositories = (List<?>) answer.get("repositories");
        if (repositories == null || repositories.isEmpty()) {
            out.println("cannot-say");
            out.flush();
            return 0;
        }
        for (final Object item : repositories) {
            final Map<?, ?> row = (Map<?, ?>) item;
            final String repo = String.valueOf(row.get("repository"));
            final String state = String.valueOf(row.get("state"));
            if ("ahead".equals(state)) {
                out.println(repo + "  ahead  " + String.valueOf(row.get("commits")));
            } else if ("cannot-say".equals(state) && row.get("why") != null) {
                out.println(repo + "  cannot-say  " + String.valueOf(row.get("why")));
            } else {
                out.println(repo + "  " + state);
            }
        }
        out.flush();
        return 0;
    }
}
