package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.ecosystem.api.EcosystemException;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Proposes a repository from a checkout, filing it only against an approved revision. */
@picocli.CommandLine.Command(
        name = "init",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Proposes a project's repository from a checkout.")
public class ProjectInitCommand extends ProjectRepositoryCommand.Changing {
    @Parameters(paramLabel = "CHECKOUT", description = "The checkout to read.")
    private Path checkout = Path.of("");

    @Option(names = "--name", description = "The repository's name.")
    private @Nullable String name;

    @Option(names = "--project", description = "The project whose file to change.")
    private @Nullable String project;

    @Option(names = "--yes", description = "Files the proposal at --base.")
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

    private @Nullable ProjectProposal proposal;

    /** Asks the installed plugin; tests can exercise refusals without a socket. */
    EcosystemDetector detector = EcosystemDetector.INSTALLED;

    ProjectSignCommand.Looks looks =
            (machine, address, place) ->
                    new ProjectFile(
                                    machine.runner(),
                                    OrganizationAccess.lent(machine),
                                    CommitSigning.agent(""),
                                    OrgAccess.projectFiles(machine))
                            .look(address, place.keys(), project());

    @Override
    String project() {
        return Objects.requireNonNull(project, "filing needs --project");
    }

    @Override
    Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException {
        if (yes && (project == null || base() == null))
            throw new Refused(
                    Refused.USAGE, "--yes needs --project and --base, the revision you read", null);
        if (!yes && (base() != null || id() != null))
            throw new Refused(Refused.USAGE, "--base and --id go with --yes", null);
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
        proposal = made;
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
            if (yes) answer.putAll(super.answer(machine));
            else {
                final String login =
                        OrgAccess.ownLogin(machine)
                                .orElseThrow(
                                        () ->
                                                new Refused(
                                                        Refused.NOT_ALLOWED,
                                                        "a project admin may change this",
                                                        null));
                final ProjectRepositoryCommand.Place place = places.of(machine, project(), login);
                final var look = looks.of(machine, place.address(), place);
                if (ProjectFile.CANNOT_SAY.equals(look.read().state()))
                    throw new Refused(
                            Refused.CANNOT_SAY, "the project repository cannot be reached", null);
                answer.put("revision", look.revision());
                answer.put("of", of());
            }
        }
        return answer;
    }

    private static Map<String, Object> plugin(String name, String state, String why) {
        return Map.of("name", name, "state", state, "why", why);
    }

    @Override
    OrganizationWrites.Written make(
            ProjectFile file,
            OrganizationWrites.Change change,
            ProjectRepositoryCommand.Place place)
            throws IOException {
        final ProjectProposal made = Objects.requireNonNull(proposal, "detection precedes filing");
        return file.propose(
                change,
                place.address(),
                place.keys(),
                String.valueOf(made.answer().get("repository")),
                made.row());
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
                        "credentials_needed",
                        "plugins",
                        "revision"))
            if (answer.containsKey(field))
                line(
                        (field.equals("plugins") ? "adapters" : field)
                                + ": "
                                + org.fuin.porelo.wire.Json.write(answer.get(field)));
    }
}
