package org.fuin.porelo.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;

/** The forge account's checked command line and private HTTP socket. */
final class ProjectRuntime {
    static final Map<String, String> SAFE_ENV =
            Map.of("PATH", "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL", "C");
    static final String ADMIN_SCOPES =
            "write:admin,write:organization,read:user,write:repository,write:package";
    private static final Map<String, String> PERSON_ENV =
            Map.of("PATH", "/usr/sbin:/usr/bin:/sbin:/bin", "LANG", "C.UTF-8");
    private final ProjectFiles files;
    private final HostFiles personFiles;
    private final Commands commands;
    private final Programs programs;
    private final SocketCheck sockets;
    private final ForgeFactory forgeFactory;

    @FunctionalInterface
    interface SocketCheck {
        boolean isSocket(Path path) throws IOException;
    }

    @FunctionalInterface
    interface ForgeFactory {
        HttpForgeAdmin open(Path path, char[] token);
    }

    @FunctionalInterface
    interface Commands {
        HostPrograms.Result run(
                List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
                throws IOException;
    }

    @FunctionalInterface
    interface Programs {
        Path checked(String path);
    }

    ProjectRuntime(ProjectFiles files) {
        this(
                files,
                (argv, input, environment, timeout) ->
                        new HostPrograms(timeout, 1024 * 1024).run(argv, input, environment),
                path ->
                        Path.of(path).isAbsolute()
                                ? SystemProgram.at(Path.of(path))
                                : SystemProgram.find(path));
    }

    ProjectRuntime(ProjectFiles files, Commands commands, Programs programs) {
        this(
                files,
                commands,
                programs,
                path ->
                        ((int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)
                                        & 0170000)
                                == 0140000,
                HttpForgeAdmin::new);
    }

    ProjectRuntime(
            ProjectFiles files,
            Commands commands,
            Programs programs,
            SocketCheck sockets,
            ForgeFactory forgeFactory) {
        this(files, commands, programs, sockets, forgeFactory, new HostFiles());
    }

    ProjectRuntime(HostFiles files) {
        this(
                files,
                (argv, input, environment, timeout) ->
                        new HostPrograms(timeout, 1024 * 1024).run(argv, input, environment),
                path ->
                        Path.of(path).isAbsolute()
                                ? SystemProgram.at(Path.of(path))
                                : SystemProgram.find(path),
                HttpForgeAdmin::new);
    }

    ProjectRuntime(
            HostFiles files, Commands commands, Programs programs, ForgeFactory forgeFactory) {
        this(
                new ProjectFiles(files.path("/"), 0),
                commands,
                programs,
                files::isSocket,
                forgeFactory,
                files);
    }

    private ProjectRuntime(
            ProjectFiles files,
            Commands commands,
            Programs programs,
            SocketCheck sockets,
            ForgeFactory forgeFactory,
            HostFiles personFiles) {
        this.personFiles = personFiles;
        this.files = files;
        this.commands = commands;
        this.programs = programs;
        this.sockets = sockets;
        this.forgeFactory = forgeFactory;
    }

    HostPrograms.Result run(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        return commands.run(argv, input, environment, timeout);
    }

    HostPrograms.Result privateFiles(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        // Git creates a directory tree: its fixed script sets the mask before any child writes.
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                programs.checked("/usr/bin/sh").toString(),
                                "-c",
                                "umask 077; exec \"$@\"",
                                "porelo-project"));
        command.addAll(argv);
        return commands.run(command, input, environment, timeout);
    }

    record Account(String login, long uid, String home) {
        List<String> switched(Path runuser, Path env, List<String> arguments) {
            final String runtime = "/run/user/" + uid;
            final List<String> result =
                    new ArrayList<>(
                            List.of(
                                    runuser.toString(),
                                    "-u",
                                    login,
                                    "--",
                                    env.toString(),
                                    "-C",
                                    "/",
                                    "HOME=" + home,
                                    "XDG_RUNTIME_DIR=" + runtime,
                                    "DBUS_SESSION_BUS_ADDRESS=unix:path=" + runtime + "/bus"));
            result.addAll(arguments);
            return result;
        }
    }

    @Nullable Account find(String login) {
        final Account account = lookup(login, PERSON_ENV);
        if (account != null && account.uid() == 0) {
            throw new HostRefusal(69, "a required account is root or has no usable absolute home");
        }
        return account;
    }

    private @Nullable Account lookup(String login, Map<String, String> environment) {
        final HostPrograms.Result result;
        try {
            result =
                    commands.run(
                            List.of("/usr/bin/getent", "passwd", login),
                            new byte[0],
                            environment,
                            Duration.ofSeconds(10));
        } catch (java.io.IOException ex) {
            throw new HostRefusal(69, "a required service or person account is missing");
        }
        if (result.exitCode() == 2) {
            return null;
        }
        final List<String> lines = result.output().lines().toList();
        final String[] fields = lines.size() == 1 ? lines.getFirst().split(":", -1) : new String[0];
        if (result.exitCode() != 0
                || fields.length != 7
                || !fields[0].equals(login)
                || !fields[2].matches("[0-9]{1,10}")
                || Long.parseLong(fields[2]) >= 4294967295L) {
            throw new HostRefusal(69, "a required service or person account is missing");
        }
        return new Account(login, Long.parseLong(fields[2]), fields[5]);
    }

    Account required(String login) {
        final Account account = find(login);
        if (account == null) {
            throw new HostRefusal(69, "a required service or person account is missing");
        }
        if (account.uid() == 0
                || !account.home().startsWith("/")
                || !java.nio.file.Path.of(account.home())
                        .normalize()
                        .toString()
                        .equals(account.home())) {
            throw new HostRefusal(69, "a required account is root or has no usable absolute home");
        }
        return account;
    }

    boolean available(String name) {
        try {
            programs.checked(name);
            return true;
        } catch (UncheckedIOException ex) {
            if (ex.getCause() instanceof SystemProgram.Missing) {
                return false;
            }
            throw new HostRefusal(69, "cannot inspect required program " + name);
        }
    }

    List<String> switched(Account account, List<String> arguments) {
        return account.switched(personProgram("runuser"), personProgram("env"), arguments);
    }

    private Path personProgram(String name) {
        try {
            return programs.checked(name);
        } catch (UncheckedIOException ex) {
            if (ex.getCause() instanceof SystemProgram.Missing) {
                throw new HostRefusal(69, name + " is missing from the fixed PATH");
            }
            throw new HostRefusal(69, "cannot inspect required program " + name);
        }
    }

    void require(String... names) {
        for (String name : names) {
            if (!available(name)) {
                throw new HostRefusal(69, name + " is missing from the fixed PATH");
            }
        }
    }

    String command(List<String> arguments, int... accepted) {
        try {
            final HostPrograms.Result result =
                    commands.run(arguments, new byte[0], PERSON_ENV, Duration.ofSeconds(60));
            return checkedResult(
                    Path.of(arguments.getFirst()).getFileName().toString(), result, accepted);
        } catch (IOException ex) {
            throw new HostRefusal(
                    69,
                    Path.of(arguments.getFirst()).getFileName() + " failed to complete (OSError)");
        }
    }

    private static String checkedResult(String name, HostPrograms.Result result, int... accepted) {
        for (int code : accepted.length == 0 ? new int[] {0} : accepted) {
            if (result.exitCode() == code) {
                return result.output();
            }
        }
        throw new HostRefusal(69, name + " failed (exit " + result.exitCode() + ")");
    }

    private Account account(String account) throws IOException {
        final HostPrograms.Result uid =
                commands.run(
                        List.of("/usr/bin/id", "-u", account),
                        new byte[0],
                        SAFE_ENV,
                        Duration.ofSeconds(10));
        final String number = uid.output().strip();
        if (uid.exitCode() != 0 || !number.matches("[1-9][0-9]*")) {
            throw new HostRefusal(
                    69, "the forge's account " + account + " is not known, or is root");
        }
        final Path bus = files.at("/run/user/" + number + "/bus");
        boolean socket = false;
        try {
            socket = sockets.isSocket(bus);
        } catch (IOException ex) {
            socket = false;
        }
        if (!socket) {
            throw new HostRefusal(
                    69,
                    "the user manager of "
                            + account
                            + " has no session bus at /run/user/"
                            + number
                            + "/bus");
        }
        final HostPrograms.Result passwd =
                commands.run(
                        List.of("/usr/bin/getent", "passwd", account),
                        new byte[0],
                        SAFE_ENV,
                        Duration.ofSeconds(10));
        final String[] fields = passwd.output().strip().split(":", -1);
        if (passwd.exitCode() != 0 || fields.length != 7 || !fields[0].equals(account)) {
            throw new HostRefusal(
                    69, "the forge's account " + account + " is not known, or is root");
        }
        final String home = fields[5];
        if (!home.startsWith("/")
                || home.equals("/")
                || !Path.of(home).normalize().toString().equals(home)) {
            throw new HostRefusal(
                    69, "the forge's account " + account + " has no usable absolute home");
        }
        return new Account(account, Long.parseLong(number), home);
    }

    HostPrograms.Result exec(String account, List<String> arguments) throws IOException {
        final Account person = account(account);
        return container(person, arguments, SAFE_ENV);
    }

    private HostPrograms.Result container(
            Account account, List<String> arguments, Map<String, String> environment)
            throws IOException {
        final boolean person = environment.equals(PERSON_ENV);
        final List<String> command =
                List.of(
                        (person ? personProgram("podman") : programs.checked("/usr/bin/podman"))
                                .toString(),
                        "exec",
                        "forgejo");
        final List<String> argv =
                new ArrayList<>(
                        person
                                ? switched(account, command)
                                : account.switched(
                                        programs.checked("/usr/sbin/runuser"),
                                        programs.checked("/usr/bin/env"),
                                        command));
        argv.addAll(arguments);
        return commands.run(argv, new byte[0], environment, Duration.ofSeconds(60));
    }

    private static List<String> adminArguments(List<String> arguments) {
        final List<String> command = new ArrayList<>(List.of("forgejo", "admin", "user"));
        command.addAll(arguments);
        return command;
    }

    HostPrograms.Result admin(String account, String... arguments) throws IOException {
        return exec(account, adminArguments(List.of(arguments)));
    }

    private static List<String> tokenArguments(String username, String name, String scopes) {
        return List.of(
                "generate-access-token",
                "--username",
                username,
                "--token-name",
                name,
                "--scopes",
                scopes,
                "--raw");
    }

    char[] token(String account, String username, String scopes) throws IOException {
        final HostPrograms.Result made =
                admin(
                        account,
                        tokenArguments(
                                        username,
                                        "porelo-host-project-"
                                                + UUID.randomUUID().toString().replace("-", ""),
                                        scopes)
                                .toArray(String[]::new));
        final String value = made.output().strip();
        if (made.exitCode() != 0 || !value.matches("[0-9a-f]{40}")) {
            throw new HostRefusal(
                    69,
                    "Forgejo made no token for " + username + " (exit " + made.exitCode() + ")");
        }
        return value.toCharArray();
    }

    HttpForgeAdmin forge(Map<String, String> values, Path conf) throws IOException {
        final String account =
                ProjectRecords.setting(values, "account", ProjectRecords.ACCOUNT, conf, 69);
        final HostPrograms.Result passwd =
                commands.run(
                        List.of("/usr/bin/getent", "passwd", account),
                        new byte[0],
                        SAFE_ENV,
                        Duration.ofSeconds(10));
        final String[] fields = passwd.output().strip().split(":", -1);
        if (passwd.exitCode() != 0 || fields.length != 7 || !fields[2].matches("[0-9]+")) {
            throw new HostRefusal(69, "the forge account is missing");
        }
        final char[] credential = token(account, "porelo-admin", ADMIN_SCOPES);
        try {
            return forgeFactory.open(
                    files.at("/run/user/" + fields[2] + "/porelo-forge/http.sock"), credential);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    HostPrograms.Result read(String account, String project, String name, String... arguments)
            throws IOException {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--git-dir=/var/lib/gitea/git/repositories/"
                                        + project
                                        + "/"
                                        + name.toLowerCase(java.util.Locale.ROOT)
                                        + ".git"));
        command.addAll(List.of(arguments));
        return exec(account, command);
    }

    record Configuration(String account, List<String> identities) {}

    @Nullable Configuration configuration(String user) {
        final List<String> values = values("account");
        if (values.isEmpty() || values.equals(List.of(""))) {
            return null;
        }
        if (values.size() != 1 || !values.getFirst().matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new HostRefusal(69, "forge.conf names no single valid account");
        }
        final List<String> agents = values("agents");
        if (agents.size() > 1) {
            throw new HostRefusal(69, "forge.conf names no valid agent list");
        }
        final List<String> identities = new ArrayList<>(List.of(user, "porelo-" + user));
        if (!agents.isEmpty() && !agents.getFirst().isEmpty()) {
            for (String agent : agents.getFirst().split(",", -1)) {
                if (!agent.matches(HostRecords.USER)) {
                    throw new HostRefusal(69, "forge.conf names no valid agent list");
                }
                identities.add(agent + "-" + user);
            }
        }
        if (new HashSet<>(identities).size() != identities.size()) {
            throw new HostRefusal(69, "forge.conf repeats an identity");
        }
        required(values.getFirst());
        require("runuser", "podman");
        return new Configuration(values.getFirst(), List.copyOf(identities));
    }

    private List<String> values(String key) {
        return personFiles
                .read("/etc/porelo-host/forge.conf")
                .lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1))
                .toList();
    }

    Session session(String user) {
        final Configuration config =
                java.util.Objects.requireNonNull(
                        configuration(user), "A forge session follows an enabled configuration");
        final String account = config.account();
        if (!account.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new HostRefusal(69, "forge.conf names no valid account");
        }
        final var entry = find(account);
        if (entry == null) {
            throw new HostRefusal(69, "the forge account is missing");
        }
        final Path socket =
                personFiles.path("/run/user/" + entry.uid() + "/porelo-forge/http.sock");
        final String name = "porelo-host-person-" + UUID.randomUUID().toString().replace("-", "");
        final String token =
                forgeCommand(account, tokenArguments("porelo-admin", name, "write:admin")).strip();
        if (!token.matches("[0-9a-f]{40}")) {
            throw new HostRefusal(69, "Forgejo made no valid access token");
        }
        final char[] credential = token.toCharArray();
        try {
            return new Session(account, name, forgeFactory.open(socket, credential));
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private String forgeCommand(String account, List<String> arguments) {
        require("runuser", "env");
        final var entry = required(account);
        final String bus = "/run/user/" + entry.uid() + "/bus";
        if (!personFiles.isSocket(personFiles.path(bus))) {
            throw new HostRefusal(
                    69, "the user manager of " + account + " has no session bus at " + bus);
        }
        try {
            return checkedResult(
                    "runuser", container(entry, adminArguments(arguments), PERSON_ENV));
        } catch (IOException ex) {
            throw new HostRefusal(69, "runuser failed to complete (OSError)");
        }
    }

    void lock(String user, Runnable removeDelivery) {
        final Configuration config = configuration(user);
        final List<HostRefusal> errors = new ArrayList<>();
        try {
            removeDelivery.run();
        } catch (HostRefusal error) {
            errors.add(error);
        }
        if (config == null) {
            if (!errors.isEmpty()) {
                throw errors.getFirst();
            }
            return;
        }
        try (Session session = session(user)) {
            final String[] lines = forgeCommand(session.account, List.of("list")).split("\\R");
            if (lines.length == 0 || !lines[0].matches("ID\\s+Username\\s+Email\\b.*")) {
                throw new HostRefusal(69, "Forgejo answered no valid user list");
            }
            final Set<String> users = new HashSet<>();
            for (int i = 1; i < lines.length; i++) {
                final String[] fields = lines[i].strip().split("\\s+");
                if (fields.length < 3 || !fields[0].matches("[0-9]+")) {
                    throw new HostRefusal(69, "Forgejo answered no valid user list");
                }
                users.add(fields[1]);
            }
            for (String identity : config.identities()) {
                if (users.contains(identity)) {
                    try {
                        session.revoke(identity);
                    } catch (HostRefusal error) {
                        errors.add(error);
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new HostRefusal(
                    errors.getFirst().exitCode(),
                    String.join(
                            "; ",
                            errors.stream()
                                    .map(error -> String.valueOf(error.getMessage()))
                                    .toList()));
        }
    }

    static final class Session implements AutoCloseable {
        private final String account;
        private final String name;
        private final HttpForgeAdmin client;

        Session(String account, String name, HttpForgeAdmin client) {
            this.account = account;
            this.name = name;
            this.client = client;
        }

        List<String> drop(String identity, @Nullable Set<String> names) {
            try {
                return client.dropNamed(identity, names);
            } catch (HttpForgeAdmin.Refusal ex) {
                throw new HostRefusal(
                        69,
                        java.util.Objects.requireNonNull(
                                ex.getMessage(), "The forge client supplies safe diagnostics"));
            }
        }

        void revoke(String identity) {
            try {
                client.requestUser(
                        "PATCH",
                        identity,
                        200,
                        Map.of("login_name", identity, "source_id", 0, "prohibit_login", true));
            } catch (HttpForgeAdmin.Refusal ex) {
                throw new HostRefusal(
                        69,
                        java.util.Objects.requireNonNull(
                                ex.getMessage(), "The forge client supplies safe diagnostics"));
            }
            drop(identity, null);
        }

        @Override
        public void close() {
            try {
                drop("porelo-admin", Set.of(name));
            } finally {
                client.close();
            }
        }
    }
}
