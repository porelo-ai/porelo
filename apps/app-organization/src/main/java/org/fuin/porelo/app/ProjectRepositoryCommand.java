package org.fuin.porelo.app;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Changes the repositories a project's own file names. */
@Command(
        name = "repository",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes the repositories a project's own file names.",
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

    /** A change of the file. */
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
                ProjectFile file, OrganizationWrites.Change change, Place place) throws IOException;
    }

    /** Names a repository. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Names a repository in the project's own file.")
    public static class Add extends Changing {

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
        OrganizationWrites.Written make(
                final ProjectFile file, final OrganizationWrites.Change change, final Place place)
                throws IOException {
            return file.add(
                    change,
                    place.address(),
                    place.keys(),
                    name,
                    upstream,
                    central,
                    review == null ? null : review(review));
        }
    }

    /** Changes what a row names. */
    @Command(
            name = "change",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Changes a row of the project's own file and keeps all else it says.")
    public static class Change extends Changing {

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
        Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
            // Before anything is opened: an agent or a forge has nothing to add to wrong use.
            if (upstream == null && central == null && review == null) {
                throw new Refused(
                        Refused.USAGE,
                        "name what changes: --upstream, --central or --review",
                        null);
            }
            if (review != null) {
                review(review);
            }
            return super.answer(machine);
        }

        @Override
        String project() {
            return project;
        }

        @Override
        OrganizationWrites.Written make(
                final ProjectFile file, final OrganizationWrites.Change change, final Place place)
                throws IOException {
            return file.changeRow(
                    change,
                    place.address(),
                    place.keys(),
                    name,
                    upstream,
                    central,
                    review == null ? null : review(review));
        }
    }

    /** Takes a repository out. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Takes a repository out of the project's own file.")
    public static class Remove extends Changing {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "NAME", description = "The repository's name.")
        private String name = "";

        @Override
        String project() {
            return project;
        }

        @Override
        OrganizationWrites.Written make(
                final ProjectFile file, final OrganizationWrites.Change change, final Place place)
                throws IOException {
            return file.remove(change, place.address(), place.keys(), name);
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
