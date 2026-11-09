package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.fuin.porelo.wire.SystemProgram;

/** The forge account's checked command line and private HTTP socket. */
final class ProjectRuntime {
    static final Map<String, String> SAFE_ENV =
            Map.of("PATH", "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL", "C");
    static final String ADMIN_SCOPES =
            "write:admin,write:organization,read:user,write:repository,write:package";
    private final ProjectFiles files;
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
                path -> SystemProgram.at(Path.of(path)));
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

    private record Account(String number, String home) {}

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
        return new Account(number, home);
    }

    HostPrograms.Result exec(String account, List<String> arguments) throws IOException {
        final Account person = account(account);
        final List<String> argv =
                new ArrayList<>(
                        List.of(
                                programs.checked("/usr/sbin/runuser").toString(),
                                "-u",
                                account,
                                "--",
                                programs.checked("/usr/bin/env").toString(),
                                "-C",
                                "/",
                                "HOME=" + person.home(),
                                "XDG_RUNTIME_DIR=/run/user/" + person.number(),
                                "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/"
                                        + person.number()
                                        + "/bus",
                                programs.checked("/usr/bin/podman").toString(),
                                "exec",
                                "forgejo"));
        argv.addAll(arguments);
        return commands.run(argv, new byte[0], SAFE_ENV, Duration.ofSeconds(60));
    }

    HostPrograms.Result admin(String account, String... arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("forgejo", "admin", "user"));
        command.addAll(List.of(arguments));
        return exec(account, command);
    }

    char[] token(String account, String username, String scopes) throws IOException {
        final HostPrograms.Result made =
                admin(
                        account,
                        "generate-access-token",
                        "--username",
                        username,
                        "--token-name",
                        "porelo-host-project-"
                                + java.util.UUID.randomUUID().toString().replace("-", ""),
                        "--scopes",
                        scopes,
                        "--raw");
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
}
