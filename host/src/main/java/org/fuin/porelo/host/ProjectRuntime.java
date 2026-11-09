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
    private static final Map<String, String> PERSON_ENV = HostPrograms.ENVIRONMENT;
    private final ProjectFiles files;
    private final HostFiles personFiles;
    private final Commands commands;
    private final Programs programs;
    private final Transfers transfers;
    private final SocketCheck sockets;
    private final ForgeFactory forgeFactory;

    @FunctionalInterface
    interface Transfers {
        HostPrograms.Result run(
                List<String> argv,
                java.io.InputStream input,
                java.io.OutputStream output,
                Map<String, String> environment,
                Duration timeout)
                throws IOException;
    }

    @FunctionalInterface
    interface SocketCheck {
        boolean isSocket(Path path) throws IOException;
    }

    @FunctionalInterface
    interface ForgeFactory {
        HttpForgeAdmin open(Path path, char[] token);

        default HttpForgeAdmin unauthenticated(Path path) {
            return HttpForgeAdmin.unauthenticated(path);
        }
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
                        new HostPrograms(timeout, outputLimit(argv)).run(argv, input, environment),
                path ->
                        Path.of(path).isAbsolute()
                                ? SystemProgram.at(Path.of(path))
                                : HostPrograms.program(path));
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
        this(files, commands, programs, sockets, forgeFactory, ProjectRuntime::transfer);
    }

    private static HostPrograms.Result transfer(
            List<String> argv,
            java.io.InputStream input,
            java.io.OutputStream output,
            Map<String, String> environment,
            Duration timeout)
            throws IOException {
        return new HostPrograms(timeout, 1024 * 1024).transfer(argv, input, output, environment);
    }

    ProjectRuntime(HostFiles files) {
        this(
                files,
                (argv, input, environment, timeout) ->
                        new HostPrograms(timeout, outputLimit(argv)).run(argv, input, environment),
                path ->
                        Path.of(path).isAbsolute()
                                ? SystemProgram.at(Path.of(path))
                                : HostPrograms.program(path),
                HttpForgeAdmin::new);
    }

    /** The most of a push verifier's answer root reads. */
    static final int MOST_PUSH_ANSWER = 64 * 1024 * 1024;

    private static int outputLimit(List<String> argv) {
        return outputLimit(argv, PushJudgement.MAX_COMMITS);
    }

    static int outputLimit(List<String> argv, int commits) {
        // The verifier's largest UTF-8 verdict is 100627 bytes; commas, framing and its newline
        // count too. Root holds the whole answer in memory, so it is also kept to 64 MiB: an
        // accepted verdict carries no reasons, and a push whose answer would not fit is not judged
        // (a 500, nothing recorded) rather than read into a gigabyte.
        return argv.stream().anyMatch(value -> value.startsWith("--unit=porelo-host-push-"))
                ? (int)
                        Math.min(
                                Math.addExact(Math.multiplyExact((long) commits, 100628L), 79L),
                                MOST_PUSH_ANSWER)
                : 1024 * 1024;
    }

    ProjectRuntime(
            HostFiles files, Commands commands, Programs programs, ForgeFactory forgeFactory) {
        this(
                new ProjectFiles(files.path("/"), 0),
                commands,
                programs,
                files::isSocket,
                forgeFactory,
                ProjectRuntime::transfer,
                files);
    }

    ProjectRuntime(
            ProjectFiles files,
            Commands commands,
            Programs programs,
            SocketCheck sockets,
            ForgeFactory forgeFactory,
            Transfers transfers) {
        this(files, commands, programs, sockets, forgeFactory, transfers, new HostFiles());
    }

    ProjectRuntime(
            ProjectFiles files,
            Commands commands,
            Programs programs,
            SocketCheck sockets,
            ForgeFactory forgeFactory,
            Transfers transfers,
            HostFiles personFiles) {
        this.personFiles = personFiles;
        this.files = files;
        this.transfers = transfers;
        this.commands = commands;
        this.programs = programs;
        this.sockets = sockets;
        this.forgeFactory = forgeFactory;
    }

    /** All nested project operations share the caller's remaining wall-clock budget. */
    ProjectRuntime until(java.time.Clock clock, java.time.Instant deadline) {
        final ForgeFactory bounded =
                new ForgeFactory() {
                    @Override
                    public HttpForgeAdmin open(Path socket, char[] token) {
                        return forgeFactory.open(socket, token).until(clock, deadline);
                    }

                    @Override
                    public HttpForgeAdmin unauthenticated(Path socket) {
                        return forgeFactory.unauthenticated(socket).until(clock, deadline);
                    }
                };
        return new ProjectRuntime(
                files,
                (argv, input, environment, timeout) ->
                        commands.run(argv, input, environment, remaining(clock, deadline, timeout)),
                programs,
                sockets,
                bounded,
                (argv, input, output, environment, timeout) ->
                        transfers.run(
                                argv,
                                input,
                                output,
                                environment,
                                remaining(clock, deadline, timeout)),
                personFiles);
    }

    private static Duration remaining(
            java.time.Clock clock, java.time.Instant deadline, Duration timeout)
            throws IOException {
        final Duration left = Duration.between(clock.instant(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new IOException("The host operation exhausted its deadline");
        }
        return timeout.isZero() || left.compareTo(timeout) < 0 ? left : timeout;
    }

    HostPrograms.Result run(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        return commands.run(argv, input, environment, timeout);
    }

    HostPrograms.Result collect(
            List<String> argv, Map<String, String> environment, Duration timeout)
            throws IOException {
        // Commit inventories have no size bound in the predecessor; stderr remains bounded.
        final var output = new java.io.ByteArrayOutputStream();
        final var result =
                transfers.run(
                        argv, java.io.InputStream.nullInputStream(), output, environment, timeout);
        return new HostPrograms.Result(
                result.exitCode(), output.toString(java.nio.charset.StandardCharsets.UTF_8));
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
        final String[] fields = passwd(login, environment);
        return fields == null ? null : new Account(login, Long.parseLong(fields[2]), fields[5]);
    }

    private String @Nullable [] passwd(String login, Map<String, String> environment) {
        final HostPrograms.Result result;
        try {
            result =
                    commands.run(
                            List.of("/usr/bin/getent", "passwd", login),
                            new byte[0],
                            environment,
                            Duration.ZERO);
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
        return fields;
    }

    @Nullable String gateLogin(String login) throws IOException {
        if (!login.matches("[a-z][a-z0-9_-]{0,31}")) return null;
        final String[] fields = passwd(login, PERSON_ENV);
        if (fields == null
                || Long.parseLong(fields[2]) < 1000
                || !login.matches("[a-z][a-z0-9_-]{0,31}")
                || !Set.of("/bin/bash", "/bin/sh", "/bin/zsh", "/usr/bin/bash", "/usr/bin/zsh")
                        .contains(fields[6])) {
            return null;
        }
        final HostPrograms.Result group =
                commands.run(
                        List.of("/usr/bin/getent", "group", "porelo-people"),
                        new byte[0],
                        PERSON_ENV,
                        Duration.ofSeconds(10));
        final String[] people = group.output().strip().split(":", -1);
        if (group.exitCode() != 0 || people.length != 4 || !people[0].equals("porelo-people")) {
            return null;
        }
        return people[2].equals(fields[3])
                        || Arrays.asList(people[3].split(",", -1)).contains(login)
                ? login
                : null;
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

    Path personProgram(String name) {
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
                    Path.of(arguments.getFirst()).getFileName()
                            + " failed to complete ("
                            + HostPrograms.failureKind(ex)
                            + ")");
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

    private List<String> execCommand(String account, List<String> arguments, boolean interactive)
            throws IOException {
        return containerCommand(account(account), arguments, SAFE_ENV, interactive);
    }

    private HostPrograms.Result container(
            Account account, List<String> arguments, Map<String, String> environment)
            throws IOException {
        return commands.run(
                containerCommand(account, arguments, environment, false),
                new byte[0],
                environment,
                Duration.ofSeconds(60));
    }

    private List<String> containerCommand(
            Account account,
            List<String> arguments,
            Map<String, String> environment,
            boolean interactive) {
        final boolean person = environment.equals(PERSON_ENV);
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                (person
                                                ? personProgram("podman")
                                                : programs.checked("/usr/bin/podman"))
                                        .toString(),
                                "exec"));
        if (interactive) {
            command.add("-i");
        }
        command.add("forgejo");
        command.addAll(arguments);
        return person
                ? switched(account, command)
                : account.switched(
                        programs.checked("/usr/sbin/runuser"),
                        programs.checked("/usr/bin/env"),
                        command);
    }

    private static List<String> adminArguments(List<String> arguments) {
        final List<String> command = new ArrayList<>(List.of("forgejo", "admin", "user"));
        command.addAll(arguments);
        return command;
    }

    HostPrograms.Result exec(String account, List<String> arguments) throws IOException {
        return commands.run(
                execCommand(account, arguments, false),
                new byte[0],
                SAFE_ENV,
                Duration.ofSeconds(60));
    }

    HostPrograms.Result exec(String account, List<String> arguments, java.io.InputStream input)
            throws IOException {
        return transfers.run(
                execCommand(account, arguments, true),
                input,
                java.io.OutputStream.nullOutputStream(),
                SAFE_ENV,
                Duration.ofSeconds(60));
    }

    void bundle(String account, Path copy, java.io.OutputStream output) throws IOException {
        final var argv =
                List.of(
                        programs.checked("/usr/sbin/runuser").toString(),
                        "-u",
                        account,
                        "--",
                        programs.checked("/usr/bin/env").toString(),
                        "-C",
                        "/",
                        programs.checked("/usr/bin/git").toString(),
                        "--git-dir=" + copy,
                        "bundle",
                        "create",
                        "-",
                        "refs/heads/main");
        if (transfers
                        .run(
                                argv,
                                java.io.InputStream.nullInputStream(),
                                output,
                                SAFE_ENV,
                                Duration.ofSeconds(60))
                        .exitCode()
                != 0) {
            throw new HostRefusal(69, "the verified copy could not be read");
        }
    }

    HttpForgeAdmin open(Path socket, char[] token) {
        return forgeFactory.open(socket, token);
    }

    Path checked(String path) {
        return programs.checked(path);
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
        final Path socket =
                java.util.Objects.requireNonNull(
                        forgeSocket(account, false), "A required forge account has a socket path");
        final char[] credential = token(account, "porelo-admin", ADMIN_SCOPES);
        try {
            return forgeFactory.open(socket, credential);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    boolean forgeGone(Map<String, String> values, Path conf) throws IOException {
        final String account =
                ProjectRecords.setting(values, "account", ProjectRecords.ACCOUNT, conf, 69);
        final Path socket = forgeSocket(account, true);
        if (socket == null) {
            return true;
        }
        try (HttpForgeAdmin forge = forgeFactory.unauthenticated(socket)) {
            return !forge.answers();
        }
    }

    private @org.jspecify.annotations.Nullable Path forgeSocket(
            String account, boolean missingAllowed) throws IOException {
        final HostPrograms.Result passwd =
                commands.run(
                        List.of("/usr/bin/getent", "passwd", account),
                        new byte[0],
                        SAFE_ENV,
                        Duration.ofSeconds(10));
        if (missingAllowed && passwd.exitCode() == 2) {
            return null;
        }
        final String[] fields = passwd.output().strip().split(":", -1);
        if (passwd.exitCode() != 0 || fields.length != 7 || !fields[2].matches("[0-9]+")) {
            throw new HostRefusal(69, "the forge account is missing");
        }
        return files.at("/run/user/" + fields[2] + "/porelo-forge/http.sock");
    }

    HostPrograms.Result inventory(String account, String project, String name) throws IOException {
        return collect(
                execCommand(account, readCommand(project, name, "rev-list", "--all"), false),
                SAFE_ENV,
                Duration.ofSeconds(60));
    }

    HostPrograms.Result read(String account, String project, String name, String... arguments)
            throws IOException {
        return exec(account, readCommand(project, name, arguments));
    }

    private static List<String> readCommand(String project, String name, String... arguments) {
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
        return command;
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
        return session(user, "");
    }

    Session session(String user, String scopes) {
        final Configuration config =
                java.util.Objects.requireNonNull(
                        configuration(user), "A forge session follows an enabled configuration");
        final String account = config.account();
        final Path socket = personEndpoint(config);
        final String name = "porelo-host-person-" + UUID.randomUUID().toString().replace("-", "");
        final String token =
                forgeCommand(
                                account,
                                tokenArguments(
                                        "porelo-admin",
                                        name,
                                        "write:admin" + (scopes.isEmpty() ? "" : "," + scopes)))
                        .strip();
        if (!token.matches("[0-9a-f]{40}")) {
            throw new HostRefusal(69, "Forgejo made no valid access token");
        }
        final char[] credential = token.toCharArray();
        try {
            return new Session(account, name, forgeFactory.open(socket, credential), this);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    Path personEndpoint(Configuration config) {
        final String account = config.account();
        if (!account.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new HostRefusal(69, "forge.conf names no valid account");
        }
        final var entry = find(account);
        if (entry == null) {
            throw new HostRefusal(69, "the forge account is missing");
        }
        return personFiles.path("/run/user/" + entry.uid() + "/porelo-forge/http.sock");
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
            throw new HostRefusal(
                    69, "runuser failed to complete (" + HostPrograms.failureKind(ex) + ")");
        }
    }

    String namedToken(String account, String identity, String scopes, String name) {
        final String value = forgeCommand(account, tokenArguments(identity, name, scopes)).strip();
        if (!value.matches("[0-9a-f]{40}")) {
            throw new HostRefusal(69, "Forgejo made no valid access token");
        }
        return value;
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
        private final ProjectRuntime runtime;

        Session(String account, String name, HttpForgeAdmin client, ProjectRuntime runtime) {
            this.account = account;
            this.name = name;
            this.client = client;
            this.runtime = runtime;
        }

        String token(String identity, String scopes, String tokenName) {
            return runtime.namedToken(account, identity, scopes, tokenName);
        }

        void dropMatching(String identity, java.util.function.Predicate<String> selected) {
            try {
                client.dropMatching(identity, selected);
            } catch (HttpForgeAdmin.Refusal ex) {
                throw new HostRefusal(
                        69,
                        java.util.Objects.requireNonNull(
                                ex.getMessage(), "Forge failures carry safe diagnostics"));
            }
        }

        HttpForgeAdmin forge() {
            return client;
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
