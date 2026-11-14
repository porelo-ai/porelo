package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.SecretScan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Acknowledges a finding of the secret scan as false, on this machine: a start says it no more
 * while the line stays as it is.
 *
 * <p>The finding is looked up again in the mirror, at the branch a task starts from, and only a
 * place the scan finds is taken: what is kept is the place and a digest of its line, never the
 * line.
 */
@Command(
        name = "secret-known",
        mixinStandardHelpOptions = true,
        description =
                "Acknowledges a line a task's start says looks like a credential as none, on this"
                        + " machine, while the line stays as it is.")
public class ProjectSecretKnownCommand
        implements Callable<Integer>, PoreloFactory.ContextAware, Suggests {

    @Override
    public List<String> candidates() {
        return new ProjectInventory(context)
                .projects().stream().map(ProjectInventory.Summary::name).toList();
    }

    @Override
    public String candidateLabel() {
        return "projects";
    }

    @Parameters(index = "0", paramLabel = "<project>", description = "The project.")
    private String name;

    @Parameters(
            index = "1",
            paramLabel = "<path>:<line>",
            description = "The place the start named, as it named it.")
    private String place;

    @Option(
            names = "--repository",
            paramLabel = "<name>",
            description = "The repository it is in; the project's own when not given.")
    private String repository;

    @Option(
            names = "--from",
            paramLabel = "<branch>",
            description =
                    "The branch the start was from, as its line names it; the one a task starts"
                            + " from by default when not given.")
    private String from;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project;
        try {
            project = GateSupport.byName(context, name);
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            return 64;
        }
        final Repository chosen =
                repository == null
                        ? project.ownRepository()
                        : project.allRepositories().stream()
                                .filter(each -> each.name().equals(repository))
                                .findFirst()
                                .orElse(null);
        if (chosen == null) {
            err.println("porelo: project '" + name + "' has no repository '" + repository + "'");
            return 64;
        }
        final java.nio.file.Path mirror = GateSupport.mirror(context, project, chosen);
        final List<SecretScan.Finding> found;
        try {
            final TaskBase base = TaskBase.select(context, project, mirror, TaskBase.named(from));
            found =
                    base == null
                            ? List.of()
                            : SecretScan.scan(
                                    context.runner(), mirror, "refs/heads/" + base.branch());
        } catch (ProjectException | GateException ex) {
            err.println("porelo: " + ex.getMessage());
            return 69;
        }
        final SecretScan.Finding finding =
                found.stream().filter(each -> each.place().equals(place)).findFirst().orElse(null);
        if (finding == null) {
            err.println(
                    "porelo: nothing at "
                            + place
                            + " in "
                            + chosen.name()
                            + " looks like a credential today; name a place a start named");
            return 65;
        }
        try {
            SecretsKnown.of(context, project.name()).add(chosen.name(), finding);
        } catch (IOException ex) {
            err.println("porelo: could not keep it: " + ex.getMessage());
            return 74;
        }
        out.println(
                "known     "
                        + chosen.name()
                        + " "
                        + finding.place()
                        + " ("
                        + finding.kind()
                        + "), while the line stays as it is");
        return 0;
    }
}
