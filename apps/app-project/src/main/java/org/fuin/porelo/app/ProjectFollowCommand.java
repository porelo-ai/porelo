package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Tells this account to take a project's configuration from a repository.
 *
 * <p><strong>A person does this and nothing else does.</strong> No commit adds a project and
 * nothing discovers one: a configuration source that can enrol further configuration sources is a
 * source that grows where nobody is looking.
 */
@Command(
        name = "follow",
        mixinStandardHelpOptions = true,
        description = "Takes a project's configuration from its repository, from now on.")
public class ProjectFollowCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "The project's name.")
    private String name;

    @Parameters(
            index = "1",
            paramLabel = "<url|file>",
            description =
                    "Where its repository is: an address, fetched with this account's own credentials and"
                            + " again in the background, or a file - a bundle or a directory on this machine - read once and"
                            + " never fetched. An offline project is followed only from a file.")
    private String url;

    @picocli.CommandLine.Option(
            names = "--accept-rewrite",
            description =
                    "Takes a signed commit that is not a descendant of the one in force."
                            + " Say this only when you know why the history moved.")
    private boolean acceptRewrite;

    @Option(
            names = "--unverified",
            description =
                    "Follows without an anchor: what the repository says is applied without"
                            + " any signature being checked. Whoever may push there then decides what"
                            + " tasks here may reach. Reported as a state wherever this project is shown.")
    private boolean unverified;

    @Option(
            names = "--signed-by",
            paramLabel = "<key>",
            description =
                    "The public key this project's configuration is signed with, as"
                            + " 'ssh-ed25519 AAAA...'. Pins it for this project and follows in one"
                            + " command. Give it once per key when a commit may be signed by several."
                            + " NEVER read from the repository it verifies.")
    private java.util.List<String> signedByAll = new java.util.ArrayList<>();

    private @Nullable String signedBy;

    @Option(
            names = "--access-repository",
            paramLabel = "<path|bundle>",
            description =
                    "The project's access repository as a path or bundle on this machine, checked as a"
                            + " fetched one and used in place of the URL the project names, which is never"
                            + " fetched. Only for a project followed from a file.")
    private String accessRepository = "";

    @Option(
            names = "--dry-run",
            description = "Says whether following this would work, and records nothing.")
    private boolean dryRun;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    /**
     * Sets the machine this command acts on.
     *
     * @param context The machine.
     */
    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Follows the project and reports mirrors that cannot supply their image file.
     *
     * @return The follow's exit code.
     * @throws Exception If following cannot complete.
     */
    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (FollowedProjects.fromAFile(url)) {
            // Recorded as where it is, not where this command happened to run.
            url = java.nio.file.Path.of(url).toAbsolutePath().normalize().toString();
        }
        final String accessFrom;
        if (accessRepository.isEmpty()) {
            accessFrom = null;
        } else {
            // A project followed from an address is fetched again in the background, and nothing
            // would carry the bundle into that fetch: its URL would be fetched after all.
            if (!FollowedProjects.fromAFile(url)) {
                err.println(
                        "porelo: --access-repository is only for a project followed from a file;"
                                + " "
                                + url
                                + " is fetched again, and its access repository with it");
                err.flush();
                return 64;
            }
            final java.nio.file.Path bundle =
                    java.nio.file.Path.of(accessRepository).toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(bundle)) {
                err.println("porelo: there is nothing at " + accessRepository);
                err.flush();
                return 66;
            }
            if (!namesAnAccessRepository(url)) {
                err.println(
                        "porelo: the project file names no access repository, so "
                                + accessRepository
                                + " would not be checked; nothing was recorded");
                err.flush();
                return 65;
            }
            accessFrom = bundle.toString();
        }
        final FollowSignedBy following = new FollowSignedBy(context, accessFrom);
        // One key keeps every way it had, a fingerprint among them; several are whole keys, all
        // pinned or none.
        signedBy = signedByAll.size() == 1 ? signedByAll.get(0) : null;
        final java.util.List<String> several =
                signedByAll.size() > 1 ? signedByAll : java.util.List.of();
        final FollowSignedBy.Answer answer;
        try {
            FollowSignedBy.refuseConflicting(null, several, unverified);
            if (dryRun) {
                // Nothing is written, not even the named key: it is checked against a copy.
                if (!several.isEmpty()) {
                    final FollowSignedBy.Answer would = following.check(name, url, several);
                    for (final String fingerprint : would.signers()) {
                        out.println("would pin  " + fingerprint);
                    }
                    return checked(would.result(), out, err);
                }
                return checked(following.check(name, url, signedBy, unverified).result(), out, err);
            }
            // Once, now, rather than at the next tick: somebody who typed this wants to know
            // whether it works, and a refusal an hour later is one nobody connects to what they
            // did.
            answer =
                    several.isEmpty()
                            ? following.follow(name, url, signedBy, unverified, acceptRewrite)
                            : following.follow(name, url, several, acceptRewrite);
        } catch (final FollowSignedBy.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return !signedByAll.isEmpty() && unverified ? 64 : 70;
        } catch (final java.io.IOException ex) {
            err.println("porelo: cannot pin the key: " + ex.getMessage());
            err.flush();
            return 70;
        }
        final Reconcile.Result result = answer.result();
        if (!answer.pinned().isEmpty() && SignedBy.isFingerprint(signedBy)) {
            out.println("pinned     " + answer.pinned());
        }
        for (final String fingerprint : answer.signers()) {
            out.println("pinned     " + fingerprint);
        }
        if (!answer.recorded()) {
            // A first follow that cannot apply leaves nothing behind. The refusal is the whole
            // answer.
            err.println("not following  " + name);
            err.println("           " + result.detail());
            if (!result.refused().isEmpty()) {
                err.println("           refused    " + result.refused());
            }
            err.println("           nothing was recorded; " + name + " is not followed here");
            err.flush();
            out.flush();
            return 70;
        }

        out.println(
                "following  "
                        + name
                        + "  "
                        + url
                        + (unverified
                                ? "  (unverified - whoever can push there decides what tasks here"
                                        + " may reach)"
                                : ""));
        switch (result.outcome()) {
            case APPLIED, UNCHANGED -> {
                out.println("applied    " + result.commit());
                if (!result.detail().isEmpty()) {
                    // What else the commit did here: a local edit replaced, a key pinned or
                    // unpinned.
                    out.println("           " + result.detail());
                }
            }
            case REWRITTEN -> {
                err.println("refused    " + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
            case NOT_SIGNED, UNKNOWN_KEY, NO_ANCHOR, UNREADABLE -> {
                err.println("refused    " + result.detail());
                if (!result.refused().isEmpty()) {
                    // Which commit was turned away, beside what is running. Two questions.
                    err.println("           refused    " + result.refused());
                }
                err.println("           the project is followed; nothing of it is in force");
                err.flush();
                out.flush();
                return 70;
            }
            default -> {
                err.println(
                        result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                                + "    "
                                + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
        }
        final Project project = GateSupport.byName(context, name);
        for (final Repository repository : project.allRepositories()) {
            final MirrorFile.DefaultBranch branch =
                    MirrorFile.defaultBranch(
                            context.runner(), GateSupport.mirror(context, project, repository));
            final String warning = MirrorFile.refusal(branch, repository);
            if (warning != null) {
                err.println("porelo: warning: " + warning);
            }
        }
        err.flush();
        out.flush();
        return 0;
    }

    /**
     * Reports what a check found, without recording anything.
     *
     * @param result What following would do.
     * @param out Where a good answer goes.
     * @param err Where a refusal goes.
     * @return Exit code: zero when following would work.
     */
    private Integer checked(
            final Reconcile.Result result, final PrintWriter out, final PrintWriter err) {
        switch (result.outcome()) {
            case APPLIED, UNCHANGED -> {
                out.println("ready      following " + name + " from " + url + " would work");
                out.println("           " + result.commit() + " is what it would put in force");
                if (!result.detail().isEmpty()) {
                    out.println("           " + result.detail());
                }
                out.flush();
                return 0;
            }
            default -> {
                err.println(
                        result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                                + "    "
                                + result.detail());
                if (!result.refused().isEmpty()) {
                    err.println("           refused    " + result.refused());
                }
                err.println("           nothing was recorded");
                err.flush();
                return 70;
            }
        }
    }

    /**
     * Whether the project file at the tip of a file names an access repository, read unverified and
     * only to refuse an option it has no use for: the follow that comes after checks the file.
     */
    private boolean namesAnAccessRepository(final String source) {
        java.nio.file.Path scratch = null;
        try {
            scratch = java.nio.file.Files.createTempDirectory("porelo-follow-access-");
            final String clone = scratch.toString();
            final org.fuin.porelo.core.process.CommandRunner runner = context.runner();
            if (!runner.run(org.fuin.porelo.core.process.Command.of("git", "init", "-q", clone))
                            .successful()
                    || !runner.run(
                                    org.fuin.porelo.core.process.Command.of(
                                            "git",
                                            "-C",
                                            clone,
                                            "fetch",
                                            "--quiet",
                                            "--end-of-options",
                                            source,
                                            "HEAD"))
                            .successful()) {
                // Unreadable here is unreadable to the follow, which says so in its own words.
                return true;
            }
            final org.fuin.porelo.core.process.CommandResult shown =
                    runner.run(
                            org.fuin.porelo.core.process.Command.of(
                                    "git", "-C", clone, "show", "FETCH_HEAD:project.yml"));
            final Object loaded =
                    new org.yaml.snakeyaml.Yaml(
                                    new org.yaml.snakeyaml.constructor.SafeConstructor(
                                            new org.yaml.snakeyaml.LoaderOptions()))
                            .load(shown.standardOutput());
            return !shown.successful()
                    || loaded instanceof java.util.Map<?, ?> root
                            && root.get("access") instanceof java.util.Map<?, ?> access
                            && access.get("repository") != null;
        } catch (final java.io.IOException | RuntimeException ex) {
            return true;
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
        }
    }

    private static void deleteTree(final java.nio.file.Path root) {
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(path);
                                } catch (final java.io.IOException ex) {
                                    // A scratch directory that will not go is not worth failing.
                                }
                            });
        } catch (final java.io.IOException ex) {
            // As above.
        }
    }
}
