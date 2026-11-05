package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.ecosystem.api.EcosystemException;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.gate.LocalRepository;
import org.fuin.porelo.shield.EgressSet;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * What a first start in a checkout on the {@code default} path may take from the repository.
 *
 * <p><strong>A repository decides nothing by itself.</strong> A plugin's answer and a {@code
 * project.yml} are a stranger's input: nothing either says opens a host or chooses a package
 * without a person's yes to exactly that text, by its hash. A No has no call and keeps nothing.
 *
 * <p>One question, one implementation: the terminal and the daemon only render it. An
 * organization's project is not asked about, since it takes its signed file.
 */
public final class FirstStart {

    /** What a checkout's first start is to do. */
    public enum Outcome {
        /** Nothing is configured and something was recognized. */
        ASK_NEW,
        /** A {@code project.yml} this machine has not approved. */
        ASK_UNKNOWN_FILE,
        /** Nothing to ask. */
        KNOWN,
        /** A repository of an organization's project. */
        NOT_DEFAULT,
        /** No usable plugin is installed. */
        NO_PLUGIN,
        /** The plugins recognized nothing. */
        NOTHING_RECOGNIZED
    }

    /** Why a yes cannot be taken. */
    public enum Reason {
        /** The directory is in no git checkout. */
        NOT_A_CHECKOUT,
        /** The name is {@code default} or another local project's. */
        NAME_TAKEN,
        /** The repository gives no name a project can have. */
        NO_NAME,
        /** The file does not read as a project, is a link, or is too large. */
        FILE_UNREADABLE,
        /** The text is no longer what the hash named. */
        MOVED_ON,
        /** There is nothing here to say yes to. */
        NOTHING_ASKED,
        /** What the yes keeps could not be written. */
        NOT_WRITTEN
    }

    /** A yes that cannot be taken, as a named value. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final Reason reason;

        /**
         * Constructor.
         *
         * @param reason The named value.
         * @param detail What to tell the person.
         */
        public Refused(final Reason reason, final String detail) {
            super(detail);
            this.reason = reason;
        }

        /**
         * Returns the named value.
         *
         * @return It.
         */
        public Reason reason() {
            return reason;
        }

        /**
         * Returns what to tell the person.
         *
         * @return It.
         */
        public String detail() {
            return String.valueOf(getMessage());
        }
    }

    /**
     * One thing found.
     *
     * @param what A package, tool or other setting, by name.
     * @param value What it says; printable.
     * @param source The file it was read from; printable.
     * @param unknown Whether it is set where nothing can read it without running something; {@code
     *     value} then says why.
     */
    public record Found(String what, String value, String source, boolean unknown) {

        /**
         * Returns this as the wire has it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("what", what);
            map.put("value", unknown ? "unknown: " + value : value);
            map.put("source", source);
            return map;
        }
    }

    /**
     * What a look at a checkout answers.
     *
     * @param outcome What to do.
     * @param found What was found; printable.
     * @param hosts Each host it opens; printable.
     * @param marked The hosts only the checkout names, in no curated set.
     * @param file The text that would be written; for a file already there, every line of it and of
     *     each file it names, made printable.
     * @param project The project's name, or "".
     * @param hash What the answer names, or "".
     * @param repository The repository in the project this checkout is, or "".
     * @param target The file's path, or {@code null} when there is none to approve.
     * @param refusal Why a yes cannot be taken, or {@code null}.
     * @param notes What else a person should read, such as a plugin that failed; printable.
     * @param staged This machine's copy of what is asked about, or {@code null}.
     * @param upstream The checkout's origin, or its own path when it has no usable one; or "".
     */
    public record Answer(
            Outcome outcome,
            List<Found> found,
            List<String> hosts,
            List<String> marked,
            String file,
            String project,
            String hash,
            String repository,
            @Nullable Path target,
            @Nullable Refused refusal,
            List<String> notes,
            @Nullable StagedProject staged,
            String upstream) {

        private static Answer plain(
                final Outcome outcome,
                final String project,
                final String repository,
                final List<String> notes) {
            return new Answer(
                    outcome,
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    project,
                    "",
                    repository,
                    null,
                    null,
                    List.copyOf(notes),
                    null,
                    "");
        }

        /** Ends the question; a yes has already moved the copy away. */
        public void discard() {
            if (staged != null) {
                staged.discard();
            }
        }

        /**
         * Returns this as the wire has it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome.name());
            map.put("found", found.stream().map(Found::asMap).toList());
            map.put("hosts", hosts);
            map.put("marked", marked);
            map.put("file", file);
            map.put("project", project);
            map.put("hash", hash);
            return map;
        }
    }

    private static final String FILE_NAME = "project.yml";

    private final PoreloContext context;

    private final EcosystemDetector detector;

    /**
     * Constructor.
     *
     * @param context This machine.
     * @param detector Who asks a plugin.
     */
    public FirstStart(final PoreloContext context, final EcosystemDetector detector) {
        this.context = context;
        this.detector = detector;
    }

    /**
     * Looks at a checkout, changing nothing there; a question holds a staged copy of what it asks
     * about, which {@link Answer#discard()} or {@link #take(Answer)} ends.
     *
     * @param directory Where the person stands.
     * @return What to do.
     * @throws Refused When it is in no checkout.
     */
    public Answer look(final Path directory) throws Refused {
        final Path here;
        try {
            here = directory.toRealPath();
        } catch (IOException ex) {
            throw new Refused(Reason.NOT_A_CHECKOUT, "that directory cannot be read");
        }
        final Path top = LocalRepository.topLevel(context.runner(), here);
        if (top == null) {
            throw new Refused(Reason.NOT_A_CHECKOUT, "that directory is in no git checkout");
        }
        final String origin = origin(top);
        if (!origin.isEmpty()) {
            // Before any file in the checkout is read: it decides nothing for such a project.
            final WorkOrigin.Choice placed = WorkOrigin.organizationNaming(context, origin);
            if (placed != null) {
                return Answer.plain(
                        Outcome.NOT_DEFAULT, placed.project(), placed.repository(), List.of());
            }
        }
        for (final Path place : List.of(here, top)) {
            final Path file = place.resolve(FILE_NAME);
            if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return ofFile(file, origin, top);
            }
        }
        if (!origin.isEmpty()) {
            final WorkOrigin.Choice recorded = new ProjectApprovals(context).naming(origin);
            if (recorded != null) {
                return Answer.plain(
                        Outcome.KNOWN, recorded.project(), recorded.repository(), List.of());
            }
        }
        return detected(here, top, origin);
    }

    /**
     * Takes a yes the daemon was given by hash.
     *
     * @param directory Where the person stands.
     * @param hash What they answered to.
     * @return The project.
     * @throws Refused When it cannot be taken.
     */
    public String confirm(final Path directory, final String hash) throws Refused {
        final Answer answer = look(directory);
        try {
            if (answer.outcome() == Outcome.KNOWN && answer.target() != null) {
                if (answer.hash().equals(hash)) {
                    return answer.project();
                }
                throw new Refused(Reason.MOVED_ON, "the file is not what that hash named");
            }
            if (answer.outcome() != Outcome.ASK_NEW
                    && answer.outcome() != Outcome.ASK_UNKNOWN_FILE) {
                throw new Refused(Reason.NOTHING_ASKED, "there is nothing to say yes to here");
            }
            if (!answer.hash().equals(hash)) {
                throw new Refused(Reason.MOVED_ON, "the file is not what that hash named");
            }
            return take(answer);
        } finally {
            answer.discard();
        }
    }

    /**
     * Takes a yes to what a look answered: its copy becomes the project, whatever the checkout now
     * holds, and a new file goes into the checkout for the person to commit.
     *
     * @param answer What the person read.
     * @return The project.
     * @throws Refused When it cannot be taken: the name is checked again, as another yes may have
     *     taken it while this question waited.
     */
    public String take(final Answer answer) throws Refused {
        if (answer.refusal() != null) {
            throw answer.refusal();
        }
        final Path file = Objects.requireNonNull(answer.target(), "a question names its file");
        final StagedProject staged =
                Objects.requireNonNull(answer.staged(), "a question holds its copy");
        final Refused now = nameRefusal(answer.project(), answer.upstream());
        if (now != null) {
            throw now;
        }
        final Path copy = context.paths().projects().defaultProjectFile(answer.project());
        try {
            if (answer.outcome() == Outcome.ASK_NEW) {
                write(file, answer.file());
            }
            staged.install(Objects.requireNonNull(copy.getParent(), "a place has a parent"));
            new ProjectApprovals(context).approve(answer.project(), answer.upstream());
        } catch (IOException ex) {
            throw new Refused(Reason.NOT_WRITTEN, "cannot keep it: " + printable(ex.toString()));
        }
        return answer.project();
    }

    /** Never over what is there, and never through a link. */
    private static void write(final Path file, final String text) throws IOException, Refused {
        try {
            Files.writeString(
                    file,
                    text,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            throw new Refused(Reason.MOVED_ON, "a " + FILE_NAME + " appeared since it was read");
        }
    }

    private String origin(final Path top) {
        final CommandResult remote =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        top.toString(),
                                        "remote",
                                        "get-url",
                                        "origin"));
        return RepositoryAddress.withoutCredential(
                remote.successful() ? remote.standardOutput().strip() : "");
    }

    private Path projects() {
        return context.paths().xdg().config().resolve("projects");
    }

    private Answer ofFile(final Path file, final String origin, final Path top) {
        final StagedProject staged;
        try {
            staged = StagedProject.read(projects(), file);
        } catch (StagedProject.Unreadable ex) {
            return refused(
                    file, new Refused(Reason.FILE_UNREADABLE, String.valueOf(ex.getMessage())));
        } catch (IOException ex) {
            return refused(
                    file,
                    new Refused(
                            Reason.NOT_WRITTEN, "cannot stage it: " + printable(ex.toString())));
        }
        final String hash;
        final String text;
        try {
            hash = staged.hash();
            text = staged.text();
        } catch (IOException ex) {
            staged.discard();
            return refused(
                    file,
                    new Refused(
                            Reason.NOT_WRITTEN, "cannot stage it: " + printable(ex.toString())));
        }
        final Project project = staged.project();
        final String repository = repositoryOf(project, origin, top);
        final String upstream = upstream(origin, top);
        if (holdsTheCopy(project.name(), hash, upstream)) {
            staged.discard();
            return new Answer(
                    Outcome.KNOWN,
                    List.of(),
                    List.of(),
                    List.of(),
                    text,
                    project.name(),
                    hash,
                    repository,
                    file,
                    null,
                    List.of(),
                    null,
                    upstream);
        }
        final Set<String> hosts = new LinkedHashSet<>(project.egress().domains());
        for (final Repository each : project.allRepositories()) {
            hosts.addAll(each.egress().domains());
        }
        final List<String> printed = hosts.stream().map(FirstStart::printable).toList();
        return new Answer(
                Outcome.ASK_UNKNOWN_FILE,
                foundIn(project),
                printed,
                // Nothing but the checkout names what a file in it opens.
                outsideCuratedSets(printed),
                text,
                project.name(),
                hash,
                repository,
                file,
                nameRefusal(project.name(), upstream),
                List.of(),
                staged,
                upstream);
    }

    /** What a checkout is known by: a usable origin, else its own path, as a `default` entry. */
    private static String upstream(final String origin, final Path top) {
        return !origin.isEmpty() && !origin.contains("?") && !origin.contains("#")
                ? origin
                : top.toString();
    }

    /**
     * Same file, same project: the name finds this machine's copy, holding just what was hashed,
     * and approved for this upstream.
     */
    private boolean holdsTheCopy(final String name, final String hash, final String upstream) {
        final Path copy = context.paths().projects().defaultProjectFile(name);
        if (!copy.equals(ProjectSource.resolve(context, name).file())
                || !new ProjectApprovals(context).approvedFor(name, upstream)) {
            return false;
        }
        try {
            return ProjectApprovals.hash(
                            Objects.requireNonNull(copy.getParent(), "a place has a parent"))
                    .equals(hash);
        } catch (IOException ex) {
            return false;
        }
    }

    private Answer refused(final Path file, final Refused refusal) {
        return new Answer(
                Outcome.ASK_UNKNOWN_FILE,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                "",
                file,
                refusal,
                List.of(),
                null,
                "");
    }

    private static String repositoryOf(final Project project, final String origin, final Path top) {
        for (final Repository each : project.workRepositories()) {
            if ((!origin.isEmpty() && RepositoryAddress.same(each.upstream(), origin))
                    || RepositoryAddress.same(each.upstream(), top.toString())) {
                return each.name();
            }
        }
        return "";
    }

    /**
     * What matters most in a stranger's file, ahead of its whole text, which the person also reads.
     */
    private static List<Found> foundIn(final Project project) {
        final List<Found> found = new ArrayList<>();
        found.add(found("security class", project.securityClass().name().toLowerCase(Locale.ROOT)));
        found.add(found("base image", project.baseImage()));
        project.imageSnippetLines().forEach(line -> found.add(found("image line", line)));
        if (project.packageSources() != null) {
            project.packageSources().forEach(each -> found.add(found("package source", each)));
        }
        project.egress().sets().forEach(each -> found.add(found("egress set", each)));
        project.egress().hostServices().forEach(each -> found.add(found("host service", each)));
        project.credentials()
                .forEach((id, where) -> found.add(found("credential", id + " for " + where)));
        for (final Repository each : project.allRepositories()) {
            if (each.upstream() != null) {
                found.add(found("repository", each.name() + " at " + each.upstream()));
            }
            each.egress().sets().forEach(set -> found.add(found("egress set", set)));
            each.egress().hostServices().forEach(one -> found.add(found("host service", one)));
            if (each.image() != null) {
                if (each.image().baseImage() != null) {
                    found.add(found("base image of " + each.name(), each.image().baseImage()));
                }
                if (each.image().snippet() != null) {
                    each.image()
                            .snippet()
                            .lines()
                            .forEach(
                                    line -> found.add(found("image line of " + each.name(), line)));
                }
                each.image()
                        .env()
                        .forEach(
                                (name, value) ->
                                        found.add(
                                                found(
                                                        "image setting of " + each.name(),
                                                        name + "=" + value)));
            }
        }
        return List.copyOf(found);
    }

    private static Found found(final String what, final String value) {
        return new Found(printable(what), printable(value), FILE_NAME, false);
    }

    private Answer detected(final Path here, final Path top, final String origin) {
        final List<String> notes = new ArrayList<>();
        final List<InstalledEcosystems.Description> plugins;
        try {
            plugins = InstalledEcosystems.all(context.paths().ecosystemDirectories());
        } catch (EcosystemException ex) {
            return Answer.plain(
                    Outcome.NO_PLUGIN,
                    "",
                    "",
                    List.of("the installed build-tool plugins could not be listed"));
        }
        final List<InstalledEcosystems.Description> usable = new ArrayList<>();
        for (final InstalledEcosystems.Description each : plugins) {
            if (each.refusal() == null) {
                usable.add(each);
            } else {
                notes.add(printable(each.name() + " is not used: " + each.refusal()));
            }
        }
        if (usable.isEmpty()) {
            return Answer.plain(Outcome.NO_PLUGIN, "", "", notes);
        }
        final String upstream = upstream(origin, top);
        final String repository = RepositoryAddress.nameOf(upstream);
        final String name = projectName(repository);
        final ProjectProposal proposal;
        try {
            // A repository with no origin is reached where a task in 'default' reaches it.
            proposal = new ProjectProposal(repository, upstream, null, null, name);
        } catch (IllegalArgumentException ex) {
            return Answer.plain(
                    Outcome.NOTHING_RECOGNIZED,
                    "",
                    "",
                    List.of(printable("'" + repository + "' can name no repository")));
        }
        final List<Found> found = new ArrayList<>();
        try {
            for (final InstalledEcosystems.Description each : usable) {
                try {
                    proposal.add(
                            each.name(), detector.detect(each, here, context.paths().xdg().home()));
                } catch (EcosystemRefused ex) {
                    if (ex.reason() != EcosystemRefused.Reason.NOT_RECOGNIZED) {
                        notes.add(printable(each.name() + " could not read: " + ex.detail()));
                    }
                } catch (EcosystemException | IOException ex) {
                    notes.add(printable(each.name() + " failed to answer"));
                }
            }
            final String text = String.valueOf(proposal.answer().get("draft"));
            proposal.tools()
                    .forEach(
                            one ->
                                    found.add(
                                            new Found(
                                                    printable(one.what()),
                                                    printable(one.value()),
                                                    printable(one.source()),
                                                    false)));
            proposal.unknowns()
                    .forEach(
                            one ->
                                    found.add(
                                            new Found(
                                                    printable(one.what()),
                                                    printable(one.value()),
                                                    printable(one.source()),
                                                    true)));
            proposal.credentialIds()
                    .forEach(
                            id ->
                                    found.add(
                                            new Found(
                                                    "credential needed",
                                                    printable(id),
                                                    "",
                                                    false)));
            if (!proposal.applies() && found.isEmpty()) {
                return Answer.plain(Outcome.NOTHING_RECOGNIZED, "", "", notes);
            }
            final List<String> hosts =
                    proposal.hosts().stream().map(FirstStart::printable).toList();
            final List<String> onlyHere =
                    proposal.checkoutHosts().stream().map(FirstStart::printable).toList();
            notes.add(
                    printable(
                            "a task in it takes this repository from "
                                    + upstream
                                    + ", and approved work goes back there"));
            if (ProjectReader.read(new java.io.StringReader(text), "project.yml draft")
                            .securityClass()
                    == org.fuin.porelo.core.project.SecurityClass.ONLINE) {
                found.add(found("security class", "online"));
            }
            final StagedProject staged;
            final String hash;
            try {
                staged = StagedProject.of(projects(), text);
                hash = staged.hash();
            } catch (IOException ex) {
                notes.add(printable("cannot stage a project file: " + ex));
                return Answer.plain(Outcome.NOTHING_RECOGNIZED, "", "", notes);
            }
            return new Answer(
                    Outcome.ASK_NEW,
                    List.copyOf(found),
                    hosts,
                    outsideCuratedSets(onlyHere),
                    text,
                    name,
                    hash,
                    repository,
                    here.resolve(FILE_NAME),
                    nameRefusal(name, upstream),
                    List.copyOf(notes),
                    staged,
                    upstream);
        } catch (ProjectException | IllegalArgumentException ex) {
            // Its words may repeat what it was told: a plugin's answer is a stranger's input.
            notes.add("a plugin's answer makes no project file, so none is proposed");
            return Answer.plain(Outcome.NOTHING_RECOGNIZED, "", "", notes);
        }
    }

    /** A project's name differs from its repository's: {@code OwnRepository} says why. */
    private static String projectName(final String repository) {
        final String word = repository.replace('.', '-');
        final String cut = word.length() > 55 ? word.substring(0, 55) : word;
        return cut.replaceAll("-+$", "") + "-project";
    }

    private List<String> outsideCuratedSets(final List<String> hosts) {
        final Set<String> curated = new LinkedHashSet<>();
        for (final EgressSet set : context.paths().egress().egressSets().all().values()) {
            set.domains().forEach(each -> curated.add(each.toLowerCase(Locale.ROOT)));
        }
        return hosts.stream()
                .filter(each -> !curated.contains(each.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * So a repository cannot take over a project this machine has: a name is free, or its copy was
     * approved for this upstream, whose yes may replace it.
     */
    private @Nullable Refused nameRefusal(final String name, final String upstream) {
        if (DefaultProject.is(name)) {
            return new Refused(Reason.NAME_TAKEN, "a project may not be called '" + name + "'");
        }
        if (!ProjectSource.names(context).contains(name)) {
            return null;
        }
        final Path copy = context.paths().projects().defaultProjectFile(name);
        if (copy.equals(ProjectSource.resolve(context, name).file())
                && new ProjectApprovals(context).approvedFor(name, upstream)) {
            return null;
        }
        return new Refused(
                Reason.NAME_TAKEN,
                "this machine already has a project '"
                        + name
                        + "' of another repository or other content; give this one another name in"
                        + " its "
                        + FILE_NAME);
    }

    private static String printable(final String text) {
        return RegistryText.printable(text);
    }
}
