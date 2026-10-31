package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Checks a project, and its access file, exactly as following would, and writes nothing.
 *
 * <p>For whoever must not trust a checker of their own: root's copies of a person's bundles, run as
 * a throwaway user, and a developer's laptop joining a project. The keys are only the ones named,
 * never one this machine pinned; the repositories only the paths or bundles given, so no vault,
 * home or network is needed. The answer is a contract for a program: its names are kept.
 */
@picocli.CommandLine.Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description =
                "Checks a project and its access file, from local paths or bundles, as following"
                        + " would, and writes nothing.")
public class ProjectVerifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--project",
            paramLabel = "NAME",
            description =
                    "The project's name, refused when the project file calls itself another;"
                            + " without it, the name the commit in force gives.")
    private String projectName = "";

    @Option(
            names = "--repository",
            required = true,
            paramLabel = "PATH",
            description = "The project's repository: a local path or a git bundle.")
    private String repository = "";

    @Option(
            names = "--signed-by",
            required = true,
            paramLabel = "KEY",
            description =
                    "A key the project must be signed with, 'ssh-ed25519 AAAA...'; repeat for more.")
    private List<String> signedBy = new ArrayList<>();

    @Option(
            names = "--since",
            paramLabel = "COMMIT",
            description = "The project commit taken as in force; a newer one must descend from it.")
    private String since = "";

    @Option(
            names = "--access-repository",
            paramLabel = "PATH",
            description = "The access file's repository, a local path or a git bundle.")
    private String accessRepository = "";

    @Option(
            names = "--access-since",
            paramLabel = "COMMIT",
            description = "The access commit taken as in force; a newer one must descend from it.")
    private String accessSince = "";

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

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
        final String repositoryPath;
        final String accessPath;
        try {
            final String local = notLocal(repository, accessRepository);
            if (local != null) {
                return refuse(err, 65, local);
            }
            for (final String path : List.of(repository, accessRepository)) {
                if (!path.isEmpty() && !Files.exists(Path.of(path))) {
                    return refuse(err, 66, "there is nothing at " + path);
                }
            }
            repositoryPath = Path.of(repository).toAbsolutePath().toString();
            accessPath =
                    accessRepository.isEmpty()
                            ? ""
                            : Path.of(accessRepository).toAbsolutePath().toString();
        } catch (final java.nio.file.InvalidPathException ex) {
            return refuse(err, 65, "'" + ex.getInput() + "' is not a path");
        }
        for (final String commit : List.of(since, accessSince)) {
            // Taken as a commit and nothing else: a ref such as FETCH_HEAD names whatever was just
            // fetched, which every history descends from, and a leading '-' is an option to git.
            if (!commit.isEmpty() && !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                return refuse(err, 65, "'" + printable(commit) + "' is not a commit's full hash");
            }
        }
        final Path scratch;
        try {
            scratch = Files.createTempDirectory("porelo-verify-");
        } catch (final IOException ex) {
            return refuse(err, 70, "cannot make a scratch directory: " + ex.getMessage());
        }
        try {
            return verify(isolated(scratch), out, err, repositoryPath, accessPath);
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * A context that reads nothing of this account: no git configuration, which can redirect a path
     * to a network or run a hook, and no credentials, vault, signers or egress sets, which live in
     * its home. What a check needs is in the paths it was given.
     */
    private PoreloContext isolated(final Path scratch) {
        final CommandRunner runner =
                command ->
                        context.runner()
                                .run(
                                        command.withEnvironment(
                                                Map.of(
                                                        "GIT_CONFIG_GLOBAL",
                                                        "/dev/null",
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1")));
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> scratch.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> scratch.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> scratch.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> scratch.resolve("run").toString();
                                    default -> null;
                                },
                        scratch);
        return new PoreloContext(runner, new PoreloPaths(xdg, scratch.resolve("bin")), args -> 70);
    }

    private int verify(
            final PoreloContext isolated,
            final PrintWriter out,
            final PrintWriter err,
            final String repositoryPath,
            final String accessPath) {
        final String name;
        try {
            name = projectName.isEmpty() ? nameIn(isolated, repositoryPath) : projectName;
        } catch (final ProjectException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final Reconcile.Checked checked;
        try {
            checked =
                    new FollowSignedBy(isolated)
                            .verified(
                                    name, repositoryPath, signedBy, since, accessPath, accessSince);
        } catch (final FollowSignedBy.Refused ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final Reconcile.Result result = checked.result();
        final Project project = checked.project();
        if (checked.projectFile() == null || project == null) {
            return refuse(err, 65, "the project file of " + name + " " + failed(result));
        }
        if (!project.name().equals(name)) {
            return refuse(
                    err,
                    65,
                    "the project file calls itself '"
                            + project.name()
                            + "', and '"
                            + name
                            + "' was checked");
        }
        final String unused = unused(project.access());
        if (unused != null) {
            return refuse(err, 65, unused);
        }
        final @Nullable AccessFile access;
        try {
            if (project.access() != null && checked.accessFile() == null) {
                return refuse(
                        err,
                        65,
                        "the access file of "
                                + name
                                + " did not pass its check ("
                                + result.accessOutcome().toLowerCase(java.util.Locale.ROOT)
                                + (result.detail().isEmpty() ? "" : ": " + result.detail())
                                + ")");
            }
            access =
                    checked.accessFile() == null
                            ? null
                            : AccessFile.read(new StringReader(checked.accessFile()), "access.yml");
        } catch (final ProjectException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        for (final org.fuin.porelo.vault.AccessPersons.Person person : checked.persons()) {
            if (person.revoked()) {
                diagnostic(err, person.login() + " revoked: " + person.refusal());
            }
        }
        err.flush();
        final Map<String, Object> answer =
                answer(
                        project,
                        result,
                        checked.key(),
                        checked.signers(),
                        access,
                        checked.persons());
        final String written;
        try {
            written = org.fuin.porelo.wire.Json.write(answer);
        } catch (final RuntimeException ex) {
            // A signed file may hold a value YAML reads and JSON has no form for, a date say: the
            // check passed, and an answer a program would misread is no answer.
            return refuse(
                    err, 65, "the settings of " + name + " cannot be answered: " + ex.getMessage());
        }
        if (json) {
            out.println(written);
        } else {
            out.println("verified  " + name + " at " + result.commit());
            if (access != null) {
                out.println("access    " + result.accessCommit());
            }
            out.println("signed by " + checked.key());
            checked.signers().forEach(key -> out.println("in force  " + key));
        }
        out.flush();
        return 0;
    }

    /**
     * Names an access option the project file gives no use for, which would otherwise pass
     * unchecked while the caller believes it was.
     */
    private @Nullable String unused(final @Nullable Access access) {
        if (!accessRepository.isEmpty() && !(access instanceof Access.InRepository)) {
            return "the project file names no access repository, so "
                    + accessRepository
                    + " was not checked";
        }
        if (!accessSince.isEmpty() && !(access instanceof Access.InRepository)) {
            return access == null
                    ? "the project file names no access file, so --access-since has nothing to"
                            + " check"
                    : "the access file lies beside the project file, so its commit is the"
                            + " project's: give it as --since";
        }
        return null;
    }

    /** The answer a program reads; its names are kept. */
    private static Map<String, Object> answer(
            final Project project,
            final Reconcile.Result result,
            final @Nullable String key,
            final List<String> signers,
            final @Nullable AccessFile access,
            final List<org.fuin.porelo.vault.AccessPersons.Person> persons) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("project", project.name());
        answer.put("commit", result.commit());
        answer.put("access_commit", access == null ? "" : result.accessCommit());
        answer.put("signer", key == null ? "" : key);
        answer.put("signers", signers);
        answer.put("host", access == null || access.host() == null ? null : host(access.host()));
        answer.put("persons", persons.stream().map(ProjectVerifyCommand::person).toList());
        answer.put(
                "admins",
                persons.stream()
                        .filter(org.fuin.porelo.vault.AccessPersons.Person::admitted)
                        .filter(person -> person.role().equals("admin"))
                        .map(org.fuin.porelo.vault.AccessPersons.Person::login)
                        .toList());
        answer.put(
                "certifiers",
                persons.stream()
                        .filter(org.fuin.porelo.vault.AccessPersons.Person::admitted)
                        .map(
                                person ->
                                        "task:"
                                                + project.name()
                                                + ":* cert-authority,namespaces=\"git\" "
                                                + person.ssh())
                        .toList());
        answer.put(
                "transports", access == null ? project.mail().transports() : access.transports());
        return answer;
    }

    private static Map<String, Object> host(final AccessFile.Host host) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("address", host.address());
        map.put("ssh_host_key", host.sshHostKey());
        return map;
    }

    private static Map<String, Object> person(
            final org.fuin.porelo.vault.AccessPersons.Person person) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("login", person.login());
        map.put("ssh", person.ssh());
        map.put("role", person.role());
        map.put("hosts", person.hosts());
        map.put("expires", person.expires());
        map.put("revoked", person.revoked());
        map.put("locked", person.locked());
        return map;
    }

    private static String failed(final Reconcile.Result result) {
        return "did not pass its check ("
                + result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                + (result.detail().isEmpty() ? "" : ": " + result.detail())
                + ")";
    }

    /**
     * Refuses anything but a local path or bundle: a URL would be fetched over a network, with
     * whatever credential this account holds, which is what a check must not need.
     */
    private static @Nullable String notLocal(final String... paths) {
        for (final String path : paths) {
            // A file that is there is a file, whatever its name holds: it is fetched by its
            // absolute path, which git never reads as a host.
            if (path.isEmpty() || Files.exists(Path.of(path))) {
                continue;
            }
            if (path.contains("://") || path.matches("(?s)[^/]*:.*")) {
                return "'"
                        + printable(path)
                        + "' is not a local path or bundle; a check fetches nothing over a network";
            }
        }
        return null;
    }

    /**
     * The name the project file in force calls itself: at {@code --since} when given, so a later
     * commit under another name is refused as a follow refuses it, else at the tip. Read only to
     * know whose keys to check with, and refused unless it has a name's shape.
     */
    private String nameIn(final PoreloContext context, final String path) throws ProjectException {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("porelo-verify-name-");
            final String clone = scratch.toString();
            final CommandResult made = context.runner().run(Command.of("git", "init", "-q", clone));
            final CommandResult fetched =
                    made.successful()
                            ? context.runner()
                                    .run(
                                            Command.of(
                                                    "git",
                                                    "-C",
                                                    clone,
                                                    "fetch",
                                                    "--quiet",
                                                    "--end-of-options",
                                                    path,
                                                    "HEAD"))
                            : made;
            if (!fetched.successful()) {
                throw new ProjectException(
                        "cannot read " + path + ": " + fetched.standardError().strip());
            }
            CommandResult shown =
                    since.isEmpty()
                            ? fetched
                            : context.runner()
                                    .run(
                                            Command.of(
                                                    "git",
                                                    "-C",
                                                    clone,
                                                    "show",
                                                    since + ":project.yml"));
            if (since.isEmpty() || !shown.successful()) {
                // A commit in force the repository does not hold: the check names the rewrite.
                shown =
                        context.runner()
                                .run(
                                        Command.of(
                                                "git",
                                                "-C",
                                                clone,
                                                "show",
                                                "FETCH_HEAD:project.yml"));
            }
            if (!shown.successful()) {
                throw new ProjectException(path + " holds no project.yml at its HEAD");
            }
            return org.fuin.porelo.core.project.ProjectReader.name(
                    new StringReader(shown.standardOutput()), "project.yml in " + path);
        } catch (final IOException ex) {
            throw new ProjectException(
                    "cannot make a scratch clone to read " + path + ": " + ex.getMessage());
        } catch (final RuntimeException ex) {
            if (ex instanceof ProjectException refused) {
                throw refused;
            }
            throw new ProjectException(path + " does not read as a project: " + ex.getMessage());
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
        }
    }

    private static void deleteTree(final Path root) {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (final IOException ex) {
                                    // A scratch directory that will not go is not worth failing.
                                }
                            });
        } catch (final IOException ex) {
            // As above.
        }
    }

    /** A value as a message may show it: one line, whatever it held. */
    private static String printable(final String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }

    private static void diagnostic(final PrintWriter err, final String message) {
        err.println(printable(message));
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        diagnostic(err, "porelo: " + message);
        err.flush();
        return code;
    }
}
