package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.core.project.file.ProjectInfo;
import org.fuin.porelo.core.project.file.RepositoryEntry;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shows the entry that changes the repositories a project's own file names. Porelo changes no
 * project file that exists: a project admin writes the entry and signs that commit.
 */
@Command(
        name = "repository",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Shows the entry a project admin writes to change the repositories a project's own"
                        + " file names.",
        subcommands = {
            ProjectRepositoryCommand.Add.class,
            ProjectRepositoryCommand.Change.class,
            ProjectRepositoryCommand.Remove.class
        })
public class ProjectRepositoryCommand {

    /**
     * What a change of the file stands on, as the organization answers it.
     *
     * @param address The project repository.
     * @param keys The project's signing keys.
     */
    record Place(String address, List<SigningKey> keys) {}

    /** Reads it; replaced by a test, which has no forge to read it from. */
    @FunctionalInterface
    interface Places {
        Place of(PoreloContext machine, String project, String login) throws IOException;
    }

    /** A signed change of the file that leaves what it says as it is: signing it anew. */
    abstract static class Changing extends OrgWriting {

        Places places =
                (machine, project, login) ->
                        ProjectRepositoryCommand.place(machine, opener, project, login);

        @Override
        final String of() {
            return "project";
        }

        @Override
        final OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return make(
                    opened.files(), change, places.of(machine, project(), change.acting().login()));
        }

        abstract String project();

        abstract OrganizationWrites.Written make(
                org.fuin.porelo.organization.gittree.ProjectFile file,
                OrganizationWrites.Change change,
                Place place)
                throws IOException;
    }

    /**
     * A change of the file, which Porelo does not make: it refuses, and its words name the file and
     * hold the entry a project admin writes into it.
     */
    abstract static class ByHand extends OrgReading {

        @Override
        final Map<String, @Nullable Object> answer(final PoreloContext machine) {
            final String what = what();
            final int lines = what.indexOf('\n');
            throw new Refused(
                    Refused.BY_HAND,
                    words(project(), what),
                    null,
                    lines < 0 ? null : what.substring(lines + 1));
        }

        @Override
        final void text(final Map<String, @Nullable Object> answer) {
            // A refusal is all it answers.
        }

        abstract String project();

        /**
         * Says what to write, after the usage is checked.
         *
         * @return What to do in the file, the entry's lines after it.
         */
        abstract String what();
    }

    /**
     * Says where a person changes a project's own file, and what.
     *
     * @param project The project.
     * @param what What to do in the file, an entry's lines after it.
     * @return The words.
     */
    static String words(final String project, final String what) {
        return "Porelo changes no project file: in the project.yml of project '"
                + project
                + "'s own repository, "
                + what.stripTrailing()
                + "\nand commit it signed with the key of an admin of the project";
    }

    /**
     * Writes a repository's entry, or the keys of it that change, as the file holds it.
     *
     * @param name The repository.
     * @param upstream Its address, or {@code null} to say nothing of it.
     * @param central Where a host takes it from, or {@code null}.
     * @param review {@code required} or {@code optional}, or {@code null}.
     * @return The {@code repositories} section with that one entry.
     */
    static String entry(
            final String name,
            final @Nullable String upstream,
            final @Nullable String central,
            final @Nullable String review) {
        if (!name.matches(Repository.NAME_PATTERN)) {
            throw new Refused(
                    Refused.USAGE,
                    "a repository's name is lower-case letters, digits, hyphens and dots",
                    null);
        }
        try {
            if (upstream != null) {
                Repository.refuseAsOption(upstream, "--upstream");
            }
            if (central != null) {
                Repository.refuseAsOption(central, "--central");
            }
        } catch (ProjectException ex) {
            throw new Refused(Refused.USAGE, "that is no address", null);
        }
        if (central != null && upstream != null && !ProjectReader.centralFits(central, upstream)) {
            throw new Refused(
                    Refused.USAGE, "a central must end in the name its upstream ends in", null);
        }
        final Map<String, RepositoryEntry> repositories = new LinkedHashMap<>();
        repositories.put(
                name,
                new RepositoryEntry(
                        central != null ? central : upstream,
                        central != null ? central : upstream,
                        review,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null));
        return ProjectFileWriter.entry(
                new ProjectFile(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        Map.of(),
                        repositories));
    }

    /**
     * Writes the project's own review, as the file holds it.
     *
     * @param review {@code required} or {@code optional}.
     * @return The {@code project} section with that one key.
     */
    static String projectReview(final String review) {
        return ProjectFileWriter.entry(
                new ProjectFile(
                        new ProjectInfo(null, null, null, null, review, null),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        Map.of(),
                        Map.of()));
    }

    /** Names a repository. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description =
                    "Shows the entry that names a repository in the project's own file, for a"
                            + " project admin to write.")
    public static class Add extends ByHand {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "NAME", description = "The repository's name.")
        private String name = "";

        @Option(names = "--upstream", required = true, description = "Its address.")
        private String upstream = "";

        @Option(names = "--central", description = "Where a host takes it from.")
        private @Nullable String central;

        @Option(
                names = "--review",
                description = "Whether a merge needs another person: required or optional.")
        private @Nullable String review;

        @Override
        String project() {
            return project;
        }

        @Override
        String what() {
            // The reader refuses a repository named as its project, so an entry that would be
            // refused is not shown as one to write.
            if (name.equals(project)) {
                throw new Refused(
                        Refused.USAGE,
                        "a repository cannot be named as its project '"
                                + project
                                + "'; name the repository otherwise",
                        null);
            }
            return "add this entry:\n"
                    + entry(name, upstream, central, review == null ? null : review(review));
        }
    }

    /** Changes what a row names. */
    @Command(
            name = "change",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description =
                    "Shows the keys that change a row of the project's own file, for a project"
                            + " admin to write.")
    public static class Change extends ByHand {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "NAME", description = "The repository's name.")
        private String name = "";

        @Option(names = "--upstream", description = "Its address.")
        private @Nullable String upstream;

        @Option(names = "--central", description = "Where a host takes it from.")
        private @Nullable String central;

        @Option(
                names = "--review",
                description = "Whether a merge needs another person: required or optional.")
        private @Nullable String review;

        @Override
        String project() {
            return project;
        }

        @Override
        String what() {
            if (upstream == null && central == null && review == null) {
                throw new Refused(
                        Refused.USAGE,
                        "name what changes: --upstream, --central or --review",
                        null);
            }
            if (Stream.of(upstream, central, review).anyMatch(""::equals)) {
                throw new Refused(
                        Refused.USAGE,
                        "name what changes, with a value: nothing is taken away",
                        null);
            }
            return "set these keys and keep the others:\n"
                    + entry(name, upstream, central, review == null ? null : review(review));
        }
    }

    /** Takes a repository out. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Says which entry a project admin takes out of the project's own file.")
    public static class Remove extends ByHand {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "NAME", description = "The repository's name.")
        private String name = "";

        @Override
        String project() {
            return project;
        }

        @Override
        String what() {
            if (!name.matches(Repository.NAME_PATTERN)) {
                throw new Refused(
                        Refused.USAGE,
                        "a repository's name is lower-case letters, digits, hyphens and dots",
                        null);
            }
            return "take the entry '" + name + "' out of repositories, and all it holds";
        }
    }

    /**
     * Checks a word for a review.
     *
     * @param word What was given.
     * @return The word.
     */
    static String review(final String word) {
        if (!Repository.REVIEW_REQUIRED.equals(word) && !Repository.REVIEW_OPTIONAL.equals(word)) {
            throw new Refused(Refused.USAGE, "a review is required or optional", null);
        }
        return word;
    }

    private static Place place(
            final PoreloContext machine,
            final OrgAccess.Opener opener,
            final String project,
            final String login)
            throws IOException {
        final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
        if (reading.stale()) {
            throw new Refused(
                    Refused.CANNOT_SAY, "the organization cannot be reached to say who may", null);
        }
        return place(reading.answers(), project, login);
    }

    /**
     * Judges who may change a project's file.
     *
     * @param state The organization.
     * @param project The project.
     * @param login Who acts.
     * @return What the change stands on.
     */
    static Place place(
            final OrganizationQuestions state, final String project, final String login) {
        final OrganizationQuestions.Project held = admin(state, project, login);
        final String revision = state.revision();
        if (!held.filed()) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "the project has no address yet: create its repository first",
                    revision);
        }
        return new Place(
                state.getProjectRepository(project).orElseThrow(), state.signingKeys(project));
    }

    /**
     * Judges whether a person is an unlocked admin of a project.
     *
     * @param state The organization.
     * @param project The project.
     * @param login Who acts.
     * @return The project.
     */
    static OrganizationQuestions.Project admin(
            final OrganizationQuestions state, final String project, final String login) {
        final String revision = state.revision();
        final OrganizationQuestions.Project held =
                state.getProjects().stream()
                        .filter(each -> each.project().equals(project))
                        .findFirst()
                        .orElseThrow(
                                () -> new Refused(Refused.NOT_FOUND, "no such project", revision));
        final OrganizationQuestions.Member member =
                state.getProjectMembers(project).orElse(List.of()).stream()
                        .filter(each -> each.login().equals(login))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NOT_ALLOWED,
                                                "a project admin may change this",
                                                revision));
        if (member.locked()) {
            throw new Refused(Refused.LOCKED, "you are locked in this project", revision);
        }
        if (!"admin".equals(member.role())) {
            throw new Refused(Refused.NOT_ALLOWED, "a project admin may change this", revision);
        }
        return held;
    }
}
