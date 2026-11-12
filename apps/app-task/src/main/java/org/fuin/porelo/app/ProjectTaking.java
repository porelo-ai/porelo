package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;

/**
 * A start of an organization's project: reads its repository, judges its file, and places both
 * where everything else reads them.
 *
 * <p><strong>Every start reads, and otherwise only a question about a project not here
 * yet.</strong> A forge out of reach, or an organization known only from its last state, starts
 * nothing: no security check rests on a stale state. What a running task, a gate or a listing reads
 * stays as the last start placed it.
 *
 * <p><strong>The clone stands at the revision judged, never at the tip.</strong> A push between the
 * judging and the fetch changes nothing here.
 */
public final class ProjectTaking {

    private static final String MAIN = "refs/heads/main";

    private static final String KEPT = "refs/remotes/origin/main";

    /** Why a start does not go on. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final String reason;

        Refused(final int code, final String reason, final String words) {
            super(words);
            this.code = code;
            this.reason = reason;
        }

        /**
         * Returns the exit code.
         *
         * @return It.
         */
        public int code() {
            return code;
        }

        /**
         * Returns the contract's reason.
         *
         * @return It.
         */
        public String reason() {
            return reason;
        }
    }

    private final PoreloContext context;

    private final IdentityOrganization.Opener opener;

    private final FetchAccess access;

    private final OrganizationProjects projects;

    /** Keeps what was placed; replaced by a test whose record cannot be written. */
    Recording recording;

    /** Keeps what was placed. */
    interface Recording {
        void keep(OrganizationProjects.Placed placed) throws IOException;
    }

    ProjectTaking(
            final PoreloContext context,
            final IdentityOrganization.Opener opener,
            final FetchAccess access,
            final OrganizationProjects projects) {
        this.context = context;
        this.opener = opener;
        this.access = access;
        this.projects = projects;
        this.recording = projects::write;
    }

    /**
     * Takes a project for a start on this machine.
     *
     * @param context The machine.
     * @param name The project's name.
     * @throws Refused By the contract's reasons.
     */
    public static void take(final PoreloContext context, final String name) throws Refused {
        new ProjectTaking(
                        context,
                        IdentityOrganization::real,
                        OrganizationAccess.lent(context),
                        OrganizationProjects.of(context))
                .taken(name);
    }

    /**
     * What a question before a start does: takes a project only when none stands here yet.
     *
     * <p>An interface asks at every change in its dialog, down to a key typed. Reading at each
     * would fetch every time, and on a host take this person's other forge tokens back. The start
     * reads anew and has the last word.
     *
     * @param context The machine.
     * @param name The project's name.
     * @throws Refused By the contract's reasons.
     */
    public static void takeToAsk(final PoreloContext context, final String name) throws Refused {
        new ProjectTaking(
                        context,
                        IdentityOrganization::real,
                        OrganizationAccess.lent(context),
                        OrganizationProjects.of(context))
                .takenToAsk(name);
    }

    /**
     * See {@link #takeToAsk}.
     *
     * @param name The project's name.
     * @throws Refused By the contract's reasons.
     */
    void takenToAsk(final String name) throws Refused {
        if (ProjectSource.resolve(context, name).file() == null) {
            taken(name);
        }
    }

    /**
     * Takes a project, or leaves one that is not the organization's alone.
     *
     * @param name The project's name.
     * @throws Refused By the contract's reasons.
     */
    void taken(final String name) throws Refused {
        if (DefaultProject.is(name) || !name.matches(Project.NAME_PATTERN)) {
            return;
        }
        try {
            final OrganizationProjects.Placed host = projects.host(name);
            if (host != null) {
                final String from = host.fetchFrom() == null ? host.address() : host.fetchFrom();
                final HostForgeAccess forge =
                        new HostForgeAccess(context, host.fetchFrom(), access);
                place(
                        name,
                        host.address(),
                        from,
                        forge,
                        // A push at this moment can take the token back: one more with a new one.
                        forge.asksFor(from) ? 2 : 1,
                        host.keys(),
                        host.homeserver(),
                        host.room());
                return;
            }
            fromTheOrganization(name);
        } catch (final HostForgeAccess.Denied ex) {
            throw new Refused(ex.code(), ex.reason(), name + " is not started: " + ex.getMessage());
        } catch (final IOException | CommandException ex) {
            throw cannotSay(name);
        }
    }

    private void fromTheOrganization(final String name) throws Refused, IOException {
        final IdentityOrganization.Reading reading;
        try {
            reading = IdentityOrganization.read(context, opener);
        } catch (final IdentityChange.Refused ex) {
            if (projects.find(name) == null && isLocal(name)) {
                return;
            }
            final String reason = ex.reason();
            throw new Refused(
                    ex.code(),
                    reason == null ? OrganizationQuestions.Refused.CANNOT_SAY : reason,
                    String.valueOf(ex.getMessage()));
        }
        if (reading == null) {
            return;
        }
        final OrganizationQuestions answers = reading.answers();
        final boolean named =
                answers.getProjects().stream().anyMatch(each -> each.project().equals(name));
        if ((reading.stale() || !answers.whole()) && (named || projects.find(name) != null)) {
            // Even with the project's own repository in reach: its keys are what is stale.
            throw cannotSay(name);
        }
        if (!named) {
            // A project the organization no longer names is this machine's no more.
            projects.forget(name);
            return;
        }
        final String address =
                answers.getProjectRepository(name)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                66,
                                                "not-opened",
                                                "the organization files no repository for "
                                                        + name));
        final List<OrganizationProjects.Key> keys = new ArrayList<>();
        for (final OrganizationQuestions.SigningKey each : answers.signingKeys(name)) {
            keys.add(
                    new OrganizationProjects.Key(
                            each.login(),
                            each.role(),
                            each.key(),
                            each.validFrom(),
                            each.validUntil(),
                            each.revokedSince()));
        }
        // Reached as this person reaches the organization: over ssh when it is named over ssh.
        String reached = address;
        try {
            reached =
                    IdentityOrganization.repository(context)
                            .map(organization -> OrganizationAccess.reached(organization, address))
                            .orElse(address);
        } catch (final IdentityChange.Refused ex) {
            // Settings that do not read say nothing of the form: the address as filed.
        }
        place(name, address, reached, access, 1, keys, null, null);
    }

    private boolean isLocal(final String name) {
        return ProjectSource.resolve(context, name).outcome() == ProjectSource.Outcome.RECORDED;
    }

    private void place(
            final String name,
            final String address,
            final String from,
            final FetchAccess fetching,
            final int tries,
            final List<OrganizationProjects.Key> keys,
            final @Nullable String homeserver,
            final @Nullable String room)
            throws Refused, IOException {
        final ProjectFile file =
                new ProjectFile(
                        context.runner(),
                        fetching,
                        CommitSigning.agent(""),
                        context.paths().projects().projectFileMemory());
        final Path clone = context.paths().projects().organizationClone(name);
        final java.util.concurrent.atomic.AtomicReference<@Nullable String> was =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Judged under the lock as well: a start judged older must not place over a newer one.
        final ProjectFile.Read read =
                FileLocks.holding(
                        context.paths().projects().organizationCloneLock(name),
                        () -> {
                            ProjectFile.Read judged = file.read(from, questionKeys(keys));
                            for (int left = tries - 1;
                                    left > 0 && ProjectFile.CANNOT_SAY.equals(judged.state());
                                    left--) {
                                judged = file.read(from, questionKeys(keys));
                            }
                            final String revision = judged.revision();
                            final OrganizationProjects.Placed before = projects.find(name);
                            was.set(before == null ? null : before.revision());
                            if (ProjectFile.READ.equals(judged.state())
                                    && revision != null
                                    && namesItself(judged, name)) {
                                fill(clone, from, revision, fetching, tries);
                                // Under the lock, in the order they were placed; before the
                                // record, so a crash between them repeats a line and loses none.
                                applied(name, address, judged, was.get());
                                recording.keep(
                                        new OrganizationProjects.Placed(
                                                name,
                                                revision,
                                                address,
                                                null,
                                                List.copyOf(keys),
                                                homeserver,
                                                room,
                                                null));
                            }
                            return judged;
                        });
        if (ProjectFile.REFUSED.equals(read.state())) {
            throw new Refused(
                    65,
                    OrganizationQuestions.Refused.REFUSED_STATE,
                    name + " is not started: " + read.why());
        }
        if (!ProjectFile.READ.equals(read.state()) || read.revision() == null) {
            throw cannotSay(name);
        }
        if (!namesItself(read, name)) {
            // Tasks are found by the name in the file: it would reach another project's.
            throw new Refused(
                    65,
                    OrganizationQuestions.Refused.REFUSED_STATE,
                    name + " is not started: its project.yml names another project");
        }
        final Project project =
                org.fuin.porelo.core.project.ProjectReader.read(clone.resolve("project.yml"));
        for (final String scheme : project.mail().conversations()) {
            try {
                ProjectMembers.settings(context, project, scheme);
            } catch (final ProjectMembers.Withheld ex) {
                throw new Refused(78, "no-homeserver", String.valueOf(ex.getMessage()));
            }
        }
    }

    /**
     * An address as the log may keep it: a query or a person's part can carry a token, and a host's
     * file is read unjudged, so what it steers a terminal with goes too.
     *
     * @param address The address.
     * @return Where it leads, and nothing else.
     */
    static String logged(final String address) {
        return StartStep.cleanLines(address)
                .replace("\n", "")
                .replaceAll("[?#].*$", "")
                .replaceAll("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@", "$1")
                .strip();
    }

    /**
     * A project's file is its permissions: one that changed shows afterwards, the same does not.
     */
    private void applied(
            final String name,
            final String address,
            final ProjectFile.Read read,
            final @Nullable String was) {
        final String revision = String.valueOf(read.revision());
        if (revision.equals(was)) {
            return;
        }
        final String signer = read.signer();
        EventLog.record(
                context,
                EventLog.PROJECT_APPLIED,
                name,
                "",
                "revision "
                        + revision
                        + " from "
                        + logged(address)
                        + (was == null ? "" : ", was " + was)
                        + (signer == null ? "" : ", signed by " + signer));
    }

    private static boolean namesItself(final ProjectFile.Read read, final String name) {
        final Project project = read.project();
        return project != null && name.equals(project.name());
    }

    private void fill(
            final Path clone,
            final String from,
            final String revision,
            final FetchAccess fetching,
            final int tries)
            throws IOException {
        if (!Files.isDirectory(clone.resolve(".git"))) {
            final Path template = Files.createTempDirectory("porelo-empty-template-");
            try {
                org.fuin.porelo.wire.Directories.create(clone);
                git(clone, Map.of(), "init", "-q", "--template=" + template, ".");
            } finally {
                Files.deleteIfExists(template);
            }
        }
        // The file judged is the bytes placed: the repository's attributes turn nothing at
        // checkout.
        final Path attributes = clone.resolve(".git/info/attributes");
        org.fuin.porelo.wire.Directories.create(attributes.getParent());
        Files.writeString(attributes, "* -text -ident -working-tree-encoding -filter\n");
        CommandResult fetched = fetch(clone, from, fetching);
        for (int left = tries - 1; left > 0 && !fetched.successful(); left--) {
            fetched = fetch(clone, from, fetching);
        }
        if (!fetched.successful()
                || !run(clone, Map.of(), List.of("cat-file", "-e", revision + "^{commit}"))
                        .successful()) {
            throw new IOException("the revision judged cannot be fetched into " + clone);
        }
        git(clone, Map.of(), "checkout", "-q", "-f", "-B", "main", revision);
        git(clone, Map.of(), "clean", "-qfdx");
    }

    private CommandResult fetch(final Path clone, final String from, final FetchAccess fetching) {
        try (FetchAccess.Lease lease = fetching.lend(from)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of("fetch", "-q", "--no-tags", "--", from, "+" + MAIN + ":" + KEPT));
            return run(clone, lease.environment(), arguments);
        }
    }

    private void git(
            final Path clone, final Map<String, String> variables, final String... arguments)
            throws IOException {
        if (!run(clone, variables, List.of(arguments)).successful()) {
            throw new IOException("git " + arguments[0] + " failed in " + clone);
        }
    }

    /** Git with nothing of this person's configuration or hooks, as the organization's is run. */
    private CommandResult run(
            final Path clone, final Map<String, String> variables, final List<String> arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.sshCommand=ssh",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "http.followRedirects=false"));
        all.addAll(arguments);
        return context.runner()
                .run(
                        Command.of(all)
                                .in(clone)
                                .withEnvironment(variables)
                                .withChosenEnvironment(
                                        Map.of(
                                                "PATH",
                                                System.getenv()
                                                        .getOrDefault("PATH", "/usr/bin:/bin"),
                                                "LC_ALL",
                                                "C",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1",
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_ATTR_NOSYSTEM",
                                                "1",
                                                "GIT_NO_REPLACE_OBJECTS",
                                                "1",
                                                "GIT_NO_LAZY_FETCH",
                                                "1",
                                                "GIT_ALLOW_PROTOCOL",
                                                "file:ssh:https",
                                                "GIT_TERMINAL_PROMPT",
                                                "0")));
    }

    private static List<OrganizationQuestions.SigningKey> questionKeys(
            final List<OrganizationProjects.Key> keys) {
        return keys.stream()
                .map(
                        each ->
                                new OrganizationQuestions.SigningKey(
                                        each.login(),
                                        each.role(),
                                        each.key(),
                                        each.validFrom(),
                                        each.validUntil(),
                                        each.revokedSince()))
                .toList();
    }

    private static Refused cannotSay(final String name) {
        return new Refused(
                69,
                OrganizationQuestions.Refused.CANNOT_SAY,
                "cannot say whether "
                        + name
                        + " may start: the organization or its repository is out of reach");
    }
}
