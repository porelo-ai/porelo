package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Shows a repository's file before a project admin or developer signs the revision they read. */
@Command(
        name = "sign-repository-file",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = {
            "Shows a repository's porelo.yml, and with --yes signs it anew.",
            "Without PROJECT and REPOSITORY it signs the checkout it runs in, or the one -C names,"
                    + " at its main: from the checkouts 'porelo setup' named of the organization's"
                    + " and the projects' repositories, with no project taken and no --base."
        })
public class ProjectSignRepositoryFileCommand extends OrgWriting {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "PROJECT",
            description = "The organization's project taken here.")
    private String project = "";

    @Parameters(
            index = "1",
            arity = "0..1",
            paramLabel = "REPOSITORY",
            description = "The repository in its project file.")
    private String repository = "";

    @Option(
            names = "--yes",
            description = "Signs it, as it stands at --base or at the checkout's main.")
    private boolean yes;

    @Option(
            names = "-C",
            paramLabel = "PATH",
            description = "The checkout to sign, without PROJECT and REPOSITORY. Default: here.")
    private @Nullable Path checkout;

    private record Target(String address, List<SigningKey> keys) {}

    /**
     * What the organization says of a project, read from its checkout.
     *
     * @param address The project repository's address, which names what this machine accepted.
     * @param keys The project's signing keys.
     */
    record Signers(String address, List<SigningKey> keys) {}

    /** Reads the organization's checkout; replaced by a test, which has no forge to ask. */
    @FunctionalInterface
    interface Organization {
        Map<String, Signers> of(PoreloContext machine, Path checkout, Set<String> projects)
                throws IOException;
    }

    Organization organization = ProjectSignRepositoryFileCommand::fromCheckout;

    /** Whether the checkout's main moved while it was signed, so it lacks the signature yet. */
    private boolean checkoutMoved;

    /** Whether this is the checkout's form, decided before its repository is found. */
    private boolean fromCheckout;

    @Override
    String of() {
        return "repository";
    }

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        if (project.isEmpty() != repository.isEmpty()) {
            throw new Refused(Refused.USAGE, "name both PROJECT and REPOSITORY, or neither", null);
        }
        fromCheckout = project.isEmpty();
        if (kept()) {
            if (base() != null) {
                throw new Refused(
                        Refused.USAGE,
                        "--base goes with PROJECT REPOSITORY: a checkout's main is signed as read",
                        null);
            }
            if (!yes && id() != null) {
                throw new Refused(Refused.USAGE, "--id goes with --yes", null);
            }
            return yes ? super.answer(machine) : lookKept(machine);
        }
        if (checkout != null) {
            throw new Refused(Refused.USAGE, "-C goes without PROJECT and REPOSITORY", null);
        }
        if (yes && base() == null) {
            throw new Refused(Refused.USAGE, "--yes needs --base, the revision you read", null);
        }
        if (!yes && (base() != null || id() != null)) {
            throw new Refused(Refused.USAGE, "--base and --id go with --yes", null);
        }
        return yes ? super.answer(machine) : look(machine);
    }

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
            throws IOException {
        if (kept()) {
            final Target target = kept(machine);
            final OrganizationWrites.Written written =
                    opened.files().signCheckout(change, here(), target.address(), target.keys());
            // Asked again, the checkout may have gone on since: only a signing made now can miss
            // it.
            checkoutMoved =
                    !written.again()
                            && !ProjectFile.checkoutMain(machine.runner(), here())
                                    .equals(written.revision());
            return written;
        }
        final Target target = target(machine);
        return opened.files().signRepositoryFile(change, target.address(), target.keys());
    }

    private boolean kept() {
        return fromCheckout;
    }

    private Path here() {
        return (checkout == null ? Path.of("") : checkout).toAbsolutePath().normalize();
    }

    /**
     * Finds the repository the checkout is of: the one whose upstream, in a project file judged in
     * a project's checkout, is the address the checkout's origin names.
     */
    private Target kept(final PoreloContext machine) throws IOException {
        final OrgCheckouts settings = OrgCheckouts.of(machine);
        final Path organizationCheckout = settings.organization();
        if (organizationCheckout == null) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine names no checkout of the organization's repository: porelo setup"
                            + " --organization-checkout PATH",
                    null);
        }
        if (settings.projects().isEmpty()) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine names no checkout of a project's repository: porelo setup"
                            + " --project-checkout PROJECT=PATH",
                    null);
        }
        final String origin = ProjectFile.checkoutOrigin(machine.runner(), here());
        final Map<String, Signers> signers =
                organization.of(machine, organizationCheckout, settings.projects().keySet());
        final ProjectFile files = reading(machine);
        final List<String> found = new ArrayList<>();
        final java.util.Set<String> upstreams = new java.util.TreeSet<>();
        @Nullable Target target = null;
        for (final Map.Entry<String, Path> each : settings.projects().entrySet()) {
            final Signers named = signers.get(each.getKey());
            if (named == null) {
                continue;
            }
            final ProjectFile.Read read =
                    files.readCheckout(
                            each.getValue(), named.address(), named.keys(), each.getKey());
            final Project judged = read.project();
            if (!ProjectFile.READ.equals(read.state()) || judged == null) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        "the project file in the checkout of "
                                + each.getKey()
                                + (ProjectFile.CANNOT_SAY.equals(read.state())
                                        ? " cannot be read"
                                        : " is not believed: " + read.why()),
                        null);
            }
            for (final Repository entry : judged.allRepositories()) {
                final String upstream = entry.upstream();
                if (upstream == null) {
                    continue;
                }
                upstreams.add(upstream);
                if (upstream.equals(origin)) {
                    found.add(each.getKey() + " " + entry.name());
                    target = new Target(upstream, named.keys());
                    project = each.getKey();
                    repository = entry.name();
                }
            }
        }
        if (found.size() > 1) {
            throw new Refused(
                    Refused.USAGE,
                    "the checkout's origin is the upstream of more than one repository: "
                            + String.join(", ", found),
                    null);
        }
        if (target == null) {
            throw new Refused(
                    Refused.NOT_FOUND,
                    "the checkout's origin is "
                            + (origin.isEmpty() ? "none" : origin)
                            + ", but the projects' checkouts name these upstreams: "
                            + String.join(", ", upstreams),
                    null);
        }
        return target;
    }

    private static ProjectFile reading(final PoreloContext machine) {
        return new ProjectFile(
                machine.runner(),
                OrganizationAccess.lent(machine),
                CommitSigning.agent(""),
                OrgAccess.projectFiles(machine));
    }

    /** Reads the organization's checkout as a machine reads its organization, fetching nothing. */
    private static Map<String, Signers> fromCheckout(
            final PoreloContext machine, final Path checkout, final Set<String> projects)
            throws IOException {
        final String address =
                OrgAccess.repository(machine)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NO_ORGANIZATION,
                                                "this machine has no organization: run porelo setup"
                                                        + " first",
                                                null));
        final OrgAccess.Opened opened = OrgAccess.real(machine, address);
        final OrganizationQuestions answers;
        try {
            answers = opened.store().readCheckout(checkout, opened.identities());
        } catch (OrganizationException | CommandException ex) {
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), null);
        }
        if (!answers.whole()) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the organization's checkout could not be judged up to its main",
                    null);
        }
        final Map<String, Signers> signers = new LinkedHashMap<>();
        for (final String name : projects) {
            answers.getProjectRepository(name)
                    .ifPresent(
                            repository ->
                                    signers.put(
                                            name,
                                            new Signers(repository, answers.signingKeys(name))));
        }
        return signers;
    }

    private Map<String, @Nullable Object> lookKept(final PoreloContext machine) throws IOException {
        final Target target = kept(machine);
        final ProjectFile.RepositoryLook shown =
                reading(machine).lookCheckout(here(), target.keys());
        return looked(shown);
    }

    private Target target(final PoreloContext machine) throws IOException {
        final OrganizationProjects.Placed placed = OrganizationProjects.of(machine).find(project);
        if (placed == null) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine has not taken the organization's project",
                    null);
        }
        final Path file = ProjectSource.resolve(machine, project).file();
        if (file == null) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine holds no judged project file: start the project first",
                    null);
        }
        final Project read = ProjectReader.read(file);
        final Repository named = read.repository(repository);
        if (named == null || named.upstream() == null) {
            throw new Refused(
                    Refused.NOT_FOUND,
                    "the project file names no upstream for that repository",
                    null);
        }
        final List<SigningKey> keys =
                placed.keys().stream()
                        .map(
                                key ->
                                        new SigningKey(
                                                key.login(),
                                                key.role(),
                                                key.key(),
                                                key.validFrom(),
                                                key.validUntil(),
                                                key.revokedSince()))
                        .toList();
        return new Target(named.upstream(), keys);
    }

    private Map<String, @Nullable Object> look(final PoreloContext machine) throws IOException {
        final Target target = target(machine);
        final ProjectFile.RepositoryLook shown =
                reading(machine).lookRepositoryFile(target.address(), target.keys());
        return looked(shown);
    }

    private Map<String, @Nullable Object> looked(final ProjectFile.RepositoryLook shown) {
        final ProjectFile.Look look = shown.look();
        final ProjectFile.Read read = look.read();
        if (ProjectFile.CANNOT_SAY.equals(read.state())) {
            throw new Refused(Refused.CANNOT_SAY, "the repository cannot be reached", null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        more(answer);
        answer.put("revision", look.revision());
        answer.put("of", of());
        answer.put("change", shown.change());
        answer.put("state", read.state());
        answer.put("why", read.why());
        answer.put("signer", read.signer());
        answer.put("file", look.text());
        answer.put("blocked", look.blocked());
        return answer;
    }

    @Override
    void more(final Map<String, @Nullable Object> answer) {
        answer.put("project", project);
        answer.put("repository", repository);
        if (kept()) {
            answer.put("checkout", here().toString());
        }
        if (checkoutMoved) {
            answer.put("checkout_moved", true);
        }
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        if (!answer.containsKey("state")) {
            super.text(answer);
            if (Boolean.TRUE.equals(answer.get("checkout_moved"))) {
                line(
                        "The checkout's main moved meanwhile: the forge holds the signature, and a"
                                + " fetch brings it");
            }
            return;
        }
        line("Project: " + answer.get("project"));
        line("Repository: " + answer.get("repository"));
        line("Revision: " + answer.get("revision"));
        line("Newest change of porelo.yml: " + answer.get("change"));
        final Object why = answer.get("why");
        line("State: " + answer.get("state") + (why == null ? "" : ": " + why));
        line("Signed by: " + answer.get("signer"));
        if (answer.get("file") instanceof String file) {
            line("The file, as signing would take it on:");
            for (final String each : List.of(file.split("\n", -1))) {
                // Anybody with write access may have put terminal controls in the file.
                line(each);
            }
        }
        if (answer.get("blocked") != null) {
            line("A signature would not do: " + answer.get("blocked"));
        } else {
            line(
                    answer.containsKey("checkout")
                            ? "Having read it: porelo project sign-repository-file -C "
                                    + answer.get("checkout")
                                    + " --yes"
                            : "Having read it: porelo project sign-repository-file "
                                    + project
                                    + " "
                                    + repository
                                    + " --yes --base "
                                    + answer.get("revision"));
        }
    }
}
