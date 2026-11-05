package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.ecosystem.api.EcosystemException;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Proposes a repository from a checkout. Porelo changes no project file that exists: for a project
 * it answers the entry a project admin adds, and {@code --yes} refuses with it.
 */
@picocli.CommandLine.Command(
        name = "init",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Proposes a project's repository from a checkout.")
public class ProjectInitCommand extends OrgReading {
    @Parameters(paramLabel = "CHECKOUT", description = "The checkout to read.")
    private Path checkout = Path.of("");

    @Option(names = "--name", description = "The repository's name.")
    private @Nullable String name;

    @Option(names = "--project", description = "The project whose file the entry is for.")
    private @Nullable String project;

    @Option(
            names = "--yes",
            description =
                    "Refused: Porelo changes no project file; says the entry a project admin"
                            + " writes.")
    private boolean yes;

    @Option(names = "--central", description = "Where a host takes the repository from.")
    private @Nullable String central;

    @Option(
            names = "--upstream",
            description =
                    "Where a task reads it, on a host its own forge's address. The checkout's"
                            + " address is then where a host takes it from.")
    private @Nullable String upstreamOption;

    @Option(
            names = "--review",
            description = "Whether a merge needs a review: required or optional.")
    private @Nullable String review;

    /** Asks the installed plugin; tests can exercise refusals without a socket. */
    EcosystemDetector detector = EcosystemDetector.INSTALLED;

    @Override
    Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException {
        if (yes && project == null) throw new Refused(Refused.USAGE, "--yes needs --project", null);
        checkout = checkout.toAbsolutePath().normalize();
        if (!Files.isDirectory(checkout))
            throw new Refused(Refused.USAGE, "CHECKOUT must be a directory", null);
        final var origin = machine.runner().run(LocalGit.origin(checkout));
        final String address = origin.successful() ? origin.trimmedOutput() : "";
        final boolean password = address.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*://[^/@]*:[^/@]*@.*");
        final boolean user =
                !address.matches("^(ssh://git@[^/@?#]+/|git@[^/:@]+:).*")
                        && (address.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*://[^/]*@.*")
                                || address.matches("^[^/:@]+@[^/:]+:.*"));
        final boolean unsafe = user || address.contains("?") || address.contains("#");
        String chosen = name;
        if (chosen == null) {
            final String path = address.replaceAll("[?#].*$", "").replaceAll("/+$", "");
            chosen =
                    address.isEmpty()
                            ? checkout.getFileName().toString()
                            : path.substring(
                                            Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'))
                                                    + 1)
                                    .replaceFirst("\\.git$", "");
        }
        final String checkedOut = address.isEmpty() || unsafe ? null : address;
        final String upstream = upstreamOption != null ? upstreamOption : checkedOut;
        final String from = central == null && upstreamOption != null ? checkedOut : central;
        final ProjectProposal made =
                new ProjectProposal(
                        chosen,
                        upstream,
                        from,
                        review == null ? null : ProjectRepositoryCommand.review(review),
                        project);
        final List<Map<String, Object>> plugins = new ArrayList<>();
        for (var description : InstalledEcosystems.all(machine.paths().ecosystemDirectories())) {
            if (description.refusal() != null) {
                plugins.add(plugin(description.name(), "refused", description.refusal()));
                continue;
            }
            try {
                made.add(
                        description.name(),
                        detector.detect(description, checkout, machine.paths().xdg().home()));
                plugins.add(plugin(description.name(), "recognized", ""));
            } catch (EcosystemRefused ex) {
                plugins.add(plugin(description.name(), ex.reason().wire(), ex.detail()));
            } catch (EcosystemException ex) {
                plugins.add(
                        plugin(
                                description.name(),
                                "refused",
                                "the ecosystem adapter failed to answer Detect"));
            }
        }
        final Map<String, @Nullable Object> answer = made.answer();
        answer.put("plugins", plugins);
        if (unsafe)
            answer.put(
                    "upstream_refusal",
                    password
                            ? "origin holds a password; upstream was left out"
                            : "origin holds a user or token part; upstream was left out");
        if (project != null) {
            answer.put("project", project);
            // The row names the repository only; the project's image and egress stay as the
            // project's file says, so what was found for them is said rather than silently lost.
            answer.put("by_hand", made.byHand());
            final String entry = made.entry();
            answer.put("entry", entry);
            if (yes)
                // Porelo changes no project file that exists: a project admin writes the entry.
                throw new Refused(
                        Refused.BY_HAND,
                        ProjectRepositoryCommand.words(project, "add this entry:\n" + entry),
                        null);
        }
        return answer;
    }

    private static Map<String, Object> plugin(String name, String state, String why) {
        return Map.of("name", name, "state", state, "why", why);
    }

    @Override
    void text(Map<String, @Nullable Object> answer) {
        for (final String each : String.valueOf(answer.get("draft")).split("\n", -1)) line(each);
        for (String field :
                List.of(
                        "upstream_refusal",
                        "disagreements",
                        "unplaced",
                        "not_placed",
                        "by_hand",
                        "credentials_needed",
                        "plugins"))
            if (answer.containsKey(field))
                line(
                        (field.equals("plugins") ? "adapters" : field)
                                + ": "
                                + org.fuin.porelo.wire.Json.write(answer.get(field)));
        if (answer.get("entry") instanceof String entry) {
            line("entry: add this to the project.yml of the project's own repository:");
            for (final String each : entry.stripTrailing().split("\n", -1)) line(each);
        }
    }
}
