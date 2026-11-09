package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** The existing project helper's arguments and refusal boundary beneath porelo-host. */
@Command(
        name = "project",
        exitCodeOnInvalidInput = 64,
        exitCodeOnExecutionException = 69,
        description = "Register, remove, migrate or take off a project.",
        subcommands = {
            HostProject.Add.class,
            HostProject.Remove.class,
            HostProject.Migrate.class,
            HostProject.TakeOff.class,
            HostProject.CentralState.class,
            HostProject.GiveBackCheck.class,
            HostProject.Copy.class,
            HostProject.Advance.class,
            HostProject.CheckCentral.class,
            HostProject.MeasureCentral.class,
            HostProject.PoolKey.class
        })
final class HostProject implements Callable<Integer> {
    private static final String USAGE =
            "usage: add <project> <admin> --signed-by \"ssh-ed25519 <base64>\" | remove <project> | take-off <project> | central-state <project> | give-back-check";
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(arity = "0..*", hidden = true)
    private List<String> invalid = new ArrayList<>();

    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final ProjectMigration.Central central;
    private final Locking locking;
    private Map<String, String> environment = Map.of();

    @FunctionalInterface
    interface Locking {
        AutoCloseable acquire(Path conf, @Nullable Integer descriptor) throws IOException;
    }

    HostProject() {
        this(new ProjectFiles());
    }

    static CommandLine commandLine() {
        return commandLine(System.getenv());
    }

    static CommandLine commandLine(Map<String, String> environment) {
        final HostProject host = new HostProject();
        host.environment = Map.copyOf(environment);
        final CommandLine command = new CommandLine(host);
        command.setEndOfOptionsDelimiter("");
        command.setUnmatchedOptionsArePositionalParams(true);
        command.getSubcommands()
                .values()
                .forEach(
                        child -> {
                            child.setEndOfOptionsDelimiter("");
                            child.setUnmatchedOptionsArePositionalParams(true);
                        });
        return command;
    }

    private HostProject(ProjectFiles files) {
        this(
                files,
                new ProjectRuntime(files),
                (origin, token) -> HttpForgeAdmin.central(java.net.URI.create(origin), token),
                ProjectLock::acquire);
    }

    HostProject(
            ProjectFiles files,
            ProjectRuntime runtime,
            ProjectMigration.Central central,
            Locking locking) {
        this.files = files;
        this.runtime = runtime;
        this.central = central;
        this.locking = locking;
    }

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("porelo-host-project: " + USAGE);
        spec.commandLine().getErr().flush();
        return 64;
    }

    abstract static class Verb implements Callable<Integer> {
        @CommandLine.ParentCommand private HostProject parent;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        @CommandLine.Parameters(arity = "0..*", hidden = true)
        private List<String> arguments = new ArrayList<>();

        @Override
        public Integer call() {
            if (!parent.invalid.isEmpty()) {
                return parent.call();
            }
            return parent.run(
                    spec.name(),
                    arguments,
                    System.in,
                    spec.commandLine().getOut(),
                    spec.commandLine().getErr());
        }
    }

    @Command(name = "add", description = "Register <project> <admin> --signed-by <key>.")
    static final class Add extends Verb {}

    @Command(name = "remove", description = "Remove <project> after checking every ref.")
    static final class Remove extends Verb {}

    @Command(
            name = "migrate",
            description = "Migrate <project>; address and token arrive as JSON on stdin.")
    static final class Migrate extends Verb {}

    @Command(
            name = "take-off",
            description = "Take a repository off <project>; JSON arrives on stdin.")
    static final class TakeOff extends Verb {}

    @Command(
            name = "central-state",
            description = "Read the central state of <project> without making a forge token.")
    static final class CentralState extends Verb {}

    @Command(
            name = "give-back-check",
            description = "Check every project main before returning the host.")
    static final class GiveBackCheck extends Verb {}

    @Command(name = "project-copy", description = "Copy <project> <address> <known-commit|->.")
    static final class Copy extends Verb {}

    @Command(
            name = "project-advance",
            description = "Advance <project> <repository> <commit> <copy> <signed|tip>.")
    static final class Advance extends Verb {}

    @Command(
            name = "check-central",
            description = "Check all refs of <project> <repository>... before down.")
    static final class CheckCentral extends Verb {}

    @Command(
            name = "measure-central",
            description = "Publish <project>'s timestamped central measurement to its members.")
    static final class MeasureCentral extends Verb {}

    @Command(name = "pool-key", description = "Send or withdraw the pool run key for all projects.")
    static final class PoolKey extends Verb {}

    int run(
            String action,
            List<String> supplied,
            InputStream input,
            PrintWriter out,
            PrintWriter err) {
        return run(action, supplied, input, out, err, false);
    }

    int run(
            String action,
            List<String> supplied,
            InputStream input,
            PrintWriter out,
            PrintWriter err,
            boolean inherited) {
        final List<String> arguments = new ArrayList<>(supplied);
        String project = "";
        try {
            if (action.equals("check-central")) {
                new ProjectCentralCheck(files, runtime).check(arguments, environment, input, err);
                return 0;
            }
            Integer descriptor = null;
            if (arguments.size() >= 2 && arguments.get(arguments.size() - 2).equals("--lock-fd")) {
                final String value = arguments.getLast();
                if (!value.matches("[0-9]{1,8}")) {
                    throw new HostRefusal(64, "the inherited lock descriptor is invalid");
                }
                descriptor = Integer.valueOf(value);
                arguments.removeLast();
                arguments.removeLast();
                inherited = true;
            }
            if (action.equals("pool-key")) {
                if (arguments.size() != 1
                        || !List.of("send", "withdraw").contains(arguments.getFirst())) {
                    throw new HostRefusal(64, "usage: pool-key send | pool-key withdraw");
                }
                final ProjectRecords records = new ProjectRecords(files);
                List<String> changed = List.of();
                final boolean send = arguments.getFirst().equals("send");
                if (send || files.exists(records.conf())) {
                    files.trusted(records.conf(), false);
                    try (AutoCloseable lock = locking.acquire(records.conf(), descriptor)) {
                        final Map<String, String> values = records.values(records.conf(), 69);
                        if (send || !runtime.forgeGone(values, records.conf())) {
                            try (HttpForgeAdmin forge = runtime.forge(values, records.conf())) {
                                final ProjectPoolKey pool = new ProjectPoolKey(files);
                                changed = send ? pool.send(forge) : pool.withdraw(forge);
                            }
                        }
                    }
                }
                output(out, ProjectRecords.object("changed", changed));
                return 0;
            }
            final int count =
                    switch (action) {
                        case "add" -> 4;
                        case "project-copy" -> 3;
                        case "project-advance" -> 5;
                        case "give-back-check" -> 0;
                        default -> 1;
                    };
            if (arguments.size() != count
                    || action.equals("add") && !arguments.get(2).equals("--signed-by")) {
                throw new HostRefusal(64, USAGE);
            }
            if (action.equals("give-back-check")) {
                files.directory(files.etc.resolve("projects"), true, true);
                final var records = new ProjectRecords(files);
                files.trusted(records.conf(), false);
                try (AutoCloseable lock = locking.acquire(records.conf(), descriptor)) {
                    final String account =
                            ProjectRecords.setting(
                                    records.values(records.conf(), 69),
                                    "account",
                                    ProjectRecords.ACCOUNT,
                                    records.conf(),
                                    69);
                    output(out, new ProjectGiveBack(files, runtime).check(account));
                }
                return 0;
            }
            if (action.equals("project-copy")
                    && !arguments.get(2).equals("-")
                    && !arguments.get(2).matches(ProjectRecords.COMMIT)) {
                throw new HostRefusal(64, "project-copy takes a known commit or -");
            }
            if (action.equals("project-advance")
                    && (!arguments.get(1).matches(ProjectRecords.REPOSITORY)
                            || !arguments.get(2).matches(ProjectRecords.COMMIT)
                            || !arguments
                                    .get(3)
                                    .matches(
                                            java.util.regex.Pattern.quote(
                                                            new ProjectCopies(files, runtime)
                                                                    .directory()
                                                                    .toString())
                                                    + "/[0-9a-f]{32}/central\\.git")
                            || !List.of("signed", "tip").contains(arguments.get(4)))) {
                throw new HostRefusal(
                        64,
                        "project-advance takes a repository's name, a commit, a copy of this helper's and signed or tip");
            }
            project = arguments.getFirst();
            if (!project.matches(ProjectRecords.PROJECT)) {
                throw new HostRefusal(
                        64,
                        "project must be 1 to 40 lowercase letters, digits or internal hyphens");
            }
            if (action.equals("add")) {
                if (!arguments.get(1).matches(ProjectRecords.PERSON)) {
                    throw new HostRefusal(64, "admin must be a registered person's user name");
                }
                ProjectRecords.signingKey(arguments.get(3), "signing key", 64);
            }
            final ProjectRecords records = new ProjectRecords(files);
            presence(action, project, records);
            for (String name : List.of("projects", "people")) {
                files.directory(files.etc.resolve(name), true, true);
            }
            if (action.equals("add")) {
                final Path person = records.person(arguments.get(1));
                if (!files.exists(person)) {
                    throw new HostRefusal(65, arguments.get(1) + " is no person on this host");
                }
                files.trusted(person, true);
            } else {
                files.trusted(records.record(project), true);
            }
            files.trusted(records.conf(), false);
            final Map<String, @Nullable Object> answer;
            try (AutoCloseable lock = locking.acquire(records.conf(), descriptor)) {
                presence(action, project, records);
                final ProjectRecords.Person person;
                final String publisher;
                final List<ProjectRecords.Affected> affected;
                if (action.equals("add")) {
                    person = records.admitted(arguments.get(1));
                    records.namedInOrganization(project, arguments.get(1));
                    publisher = "";
                    affected = List.of();
                } else if (List.of(
                                "central-state",
                                "take-off",
                                "project-copy",
                                "project-advance",
                                "measure-central")
                        .contains(action)) {
                    person = null;
                    publisher = "";
                    affected = List.of();
                } else {
                    person = null;
                    publisher = records.publisher(project);
                    affected = records.peopleAfterRemoval(project);
                }
                if (action.equals("remove")) {
                    new ProjectReadKeys(files, runtime).left(project);
                }
                final Map<String, String> values = records.values(records.conf(), 69);
                final String account =
                        ProjectRecords.setting(
                                values, "account", ProjectRecords.ACCOUNT, records.conf(), 69);
                if (action.equals("central-state")) {
                    answer = new ProjectReadKeys(files, runtime).centralState(account, project);
                } else if (action.equals("measure-central")) {
                    new ProjectMeasurements(files, runtime, java.time.Clock.systemUTC())
                            .measure(account, project);
                    answer = null;
                } else if (action.equals("project-copy")) {
                    answer =
                            new ProjectCopies(files, runtime)
                                    .copy(project, arguments.get(1), arguments.get(2));
                } else if (action.equals("project-advance")) {
                    answer =
                            new ProjectAdvance(files, runtime, account, project, arguments.get(1))
                                    .advance(
                                            arguments.get(1),
                                            arguments.get(2),
                                            Path.of(arguments.get(3)),
                                            arguments.get(4));
                } else {
                    try (HttpForgeAdmin forge = runtime.forge(values, records.conf())) {
                        final ProjectRegistration registration =
                                new ProjectRegistration(files, runtime);
                        final ProjectMigration migration =
                                new ProjectMigration(files, runtime, central);
                        answer =
                                switch (action) {
                                    case "add" ->
                                            registration.add(
                                                    forge,
                                                    account,
                                                    project,
                                                    arguments.get(1),
                                                    arguments.get(3),
                                                    java.util.Objects.requireNonNull(
                                                            person,
                                                            "Add requires the admitted person's record"));
                                    case "remove" ->
                                            registration.remove(
                                                    forge,
                                                    account,
                                                    project,
                                                    publisher,
                                                    affected,
                                                    inherited,
                                                    () -> token(input));
                                    case "migrate" -> {
                                        migration.migrate(forge, project, request(input));
                                        yield null;
                                    }
                                    case "take-off" ->
                                            migration.takeOff(
                                                    forge, account, project, request(input));
                                    default -> throw new HostRefusal(64, "unknown project verb");
                                };
                    }
                }
            }
            if (answer != null) {
                output(out, answer);
            }
            return 0;
        } catch (ProjectReadKeys.Unpushed ex) {
            if (inherited && action.equals("remove")) {
                if (!failureOutput(out, project, ex.commits)) {
                    return 73;
                }
            }
            return diagnostic(err, action, ex.getMessage(), 65);
        } catch (HostRefusal ex) {
            if (inherited && action.equals("remove") && ex.exitCode() == 65) {
                if (!failureOutput(out, project, Map.of())) {
                    return 73;
                }
            }
            return diagnostic(err, action, ex.getMessage(), ex.exitCode());
        } catch (HttpForgeAdmin.Refusal ex) {
            if (inherited && action.equals("remove") && ex.exitCode() == 65) {
                if (!failureOutput(out, project, Map.of())) {
                    return 73;
                }
            }
            return diagnostic(err, action, ex.getMessage(), ex.exitCode());
        } catch (Exception ex) {
            return diagnostic(
                    err,
                    action,
                    "dependency or file read failed: " + ex.getClass().getSimpleName(),
                    69);
        }
    }

    private static boolean failureOutput(
            PrintWriter out, String project, Map<String, @Nullable Object> unpushed) {
        try {
            output(out, ProjectRecords.object("project", project, "unpushed", unpushed));
            return true;
        } catch (HostRefusal ex) {
            return false;
        }
    }

    private static int diagnostic(
            PrintWriter err, String action, @Nullable String message, int code) {
        err.println(
                (action.equals("check-central") ? "porelo-host-push: " : "porelo-host-project: ")
                        + message);
        err.flush();
        return err.checkError() ? 73 : code;
    }

    private void presence(String action, String project, ProjectRecords records) {
        if (action.equals("add") && files.exists(records.record(project))) {
            throw new HostRefusal(65, records.record(project) + " exists");
        }
        if (!action.equals("add") && !files.exists(records.record(project))) {
            throw new HostRefusal(65, "unknown project " + project);
        }
    }

    private static String request(InputStream input) throws IOException {
        final byte[] bytes = input.readNBytes(16385);
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static char[] token(InputStream input) throws IOException {
        final char[] given;
        if (input == System.in && System.console() != null) {
            given =
                    java.util.Objects.requireNonNull(System.console(), "Terminal was checked")
                            .readPassword("Token for the central forge (not shown): ");
            if (given == null) {
                throw new HostRefusal(64, "no central forge token was given");
            }
        } else {
            final byte[] bytes = input.readNBytes(4097);
            try {
                final java.nio.CharBuffer decoded =
                        StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes));
                try {
                    int size = decoded.remaining();
                    if (size > 0 && decoded.get(size - 1) == '\n') {
                        size--;
                    }
                    given = new char[size];
                    decoded.get(given);
                } finally {
                    Arrays.fill(decoded.array(), '\0');
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        if (given.length == 0 || given.length > 4096 || containsLineBreak(given)) {
            Arrays.fill(given, '\0');
            throw new HostRefusal(64, "no valid central forge token was given");
        }
        return given;
    }

    private static boolean containsLineBreak(char[] value) {
        for (char character : value) {
            if (character == '\n' || character == '\r') {
                return true;
            }
        }
        return false;
    }

    private static void output(PrintWriter out, Object answer) {
        out.println(ProjectRecords.pythonJson(answer, true));
        out.flush();
        if (out.checkError()) {
            throw new HostRefusal(73, "cannot write standard output: IOException");
        }
    }
}
