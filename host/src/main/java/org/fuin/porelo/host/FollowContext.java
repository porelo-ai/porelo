package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One root invocation's checked files, bounded children and isolated Porelo operations. */
final class FollowContext {
    static final String ORG = "/etc/porelo-host/organization/";
    final HostFiles host;
    final ProjectFiles files;
    final ProjectRuntime runtime;
    final Clock clock;
    final PrintWriter err;
    Instant deadline;

    FollowContext(
            HostFiles host,
            ProjectFiles files,
            ProjectRuntime runtime,
            Clock clock,
            PrintWriter err) {
        this.host = host;
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
        this.err = err;
        this.deadline = Instant.MAX;
    }

    FollowContext until(Instant deadline) {
        final FollowContext bounded =
                new FollowContext(host, files, runtime.until(clock, deadline), clock, err);
        bounded.deadline = this.deadline.isBefore(deadline) ? this.deadline : deadline;
        return bounded;
    }

    Path path(String path) {
        return files.at(path);
    }

    void directories(Path path, int mode) throws IOException {
        if (!files.exists(path)) {
            directories(ProjectFiles.parent(path), 0700);
            final boolean publicDirectory =
                    List.of(
                                    path("/etc/ssh/authorized_keys"),
                                    path("/run/porelo-host"),
                                    path("/run/porelo-host/people"))
                            .contains(path);
            files.mkdir(path, publicDirectory ? 0755 : mode);
        } else {
            files.directory(path, false, false);
        }
    }

    void write(Path path, String text, int mode) throws IOException {
        directories(ProjectFiles.parent(path), 0700);
        if (files.exists(path)) {
            files.trusted(path, false);
        }
        final Path staged = files.stage(path, ProjectFiles.STAGED_PREFIX, text);
        try {
            Files.setAttribute(staged, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
            files.replace(staged, path);
        } finally {
            files.delete(staged);
        }
    }

    void write(String path, String text, int mode) throws IOException {
        write(path(path), text, mode);
    }

    void json(String path, Object value) throws IOException {
        write(path, ProjectRecords.pythonJson(value, false) + "\n", 0600);
    }

    String setting(String path, String key) {
        final List<String> values =
                host.read(path)
                        .lines()
                        .filter(line -> line.startsWith(key + "="))
                        .map(line -> line.substring(key.length() + 1))
                        .toList();
        if (values.size() != 1) {
            throw new HostRefusal(69, path + " names no single valid " + key);
        }
        return values.getFirst();
    }

    HostPrograms.Result run(List<String> arguments, byte[] input, Duration timeout)
            throws IOException {
        runtime.require(arguments.getFirst());
        final List<String> argv = new ArrayList<>(arguments);
        argv.set(0, runtime.checked(argv.getFirst()).toString());
        try {
            return runtime.run(argv, input, ProjectRuntime.SAFE_ENV, timeout);
        } catch (IOException ex) {
            throw new HostRefusal(
                    69, Path.of(argv.getFirst()).getFileName() + " failed to complete (OSError)");
        }
    }

    String command(List<String> arguments) throws IOException {
        final HostPrograms.Result result = run(arguments, new byte[0], Duration.ofSeconds(60));
        if (result.exitCode() != 0) {
            throw new HostRefusal(
                    69,
                    Path.of(arguments.getFirst()).getFileName()
                            + " failed (exit "
                            + result.exitCode()
                            + ")");
        }
        return result.output();
    }

    HostPrograms.Result as(String login, List<String> arguments, byte[] input) throws IOException {
        final var account = runtime.required(login);
        final String bus = "/run/user/" + account.uid() + "/bus";
        if (arguments.getFirst().equals("podman") && !host.isSocket(path(bus))) {
            throw new HostRefusal(
                    69, "the user manager of " + login + " has no session bus at " + bus);
        }
        runtime.require(arguments.getFirst());
        final List<String> inner = new ArrayList<>(arguments);
        inner.set(0, runtime.checked(inner.getFirst()).toString());
        return run(runtime.switched(account, inner), input, Duration.ofSeconds(60));
    }

    Path scratch(String prefix) throws IOException {
        final Path directory = path("/run/porelo-host/sync");
        directories(directory, 0700);
        files.directory(directory, true, false);
        for (Path left : files.list(directory)) {
            files.removeTree(left);
        }
        return Files.createTempDirectory(directory, prefix + ".");
    }

    Map<String, @Nullable Object> isolated(
            String unit,
            Map<String, Path> staged,
            List<String> arguments,
            String what,
            String refused,
            byte[] input,
            boolean network,
            boolean kept,
            @Nullable String user)
            throws IOException {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "systemd-run",
                                "--pipe",
                                "--wait",
                                "--quiet",
                                "--collect",
                                "--unit=" + unit));
        if (user == null) {
            command.addAll(List.of("-p", "DynamicUser=yes"));
        } else {
            command.addAll(
                    List.of(
                            "-p",
                            "User=" + user,
                            "-p",
                            "PrivateTmp=yes",
                            "-p",
                            "ProtectSystem=strict",
                            "-p",
                            "NoNewPrivileges=yes",
                            "-p",
                            "Environment=HOME=/tmp"));
        }
        command.addAll(
                List.of(
                        "-p",
                        network ? "PrivateNetwork=no" : "PrivateNetwork=yes",
                        "-p",
                        "ProtectHome=yes"));
        if (kept) {
            command.addAll(
                    List.of(
                            "-p",
                            "StateDirectory=" + unit,
                            "-p",
                            "StateDirectoryMode=0700",
                            "-p",
                            "Environment=XDG_STATE_HOME=/var/lib/" + unit));
        }
        staged.forEach(
                (name, path) ->
                        command.addAll(List.of("-p", "LoadCredential=" + name + ":" + path)));
        command.add(runtime.checked("porelo").toString());
        command.addAll(arguments);
        final HostPrograms.Result result;
        try {
            // Fetch can use the entire timer window; all other isolated operations keep Python's 60
            // s.
            result =
                    run(
                            command,
                            input,
                            Duration.ofSeconds(unit.equals("porelo-host-org-fetch") ? 300 : 60));
        } catch (IOException ex) {
            throw new HostRefusal(69, "systemd-run failed to complete (OSError)");
        }
        if (result.exitCode() != 0) {
            String words =
                    String.join(
                            "; ",
                            result.error()
                                    .lines()
                                    .map(String::strip)
                                    .filter(line -> !line.isEmpty())
                                    .map(
                                            line ->
                                                    line.startsWith("porelo: ")
                                                            ? line.substring(8)
                                                            : line)
                                    .toList());
            if (words.isEmpty()) {
                words = refused + " (exit " + result.exitCode() + ")";
            }
            final String visible = visible(words);
            words = printable(words);
            if (input.length > 0
                    && visible.contains(new String(input, StandardCharsets.US_ASCII).strip())) {
                words = refused + " (exit " + result.exitCode() + ")";
            }
            words = words.substring(0, Math.min(words.length(), 512));
            if (kept && result.exitCode() == 65) {
                refusal(words);
            }
            throw new HostRefusal(result.exitCode() == 65 ? 65 : 69, words);
        }
        final Object parsed =
                ProjectRecords.json(result.output(), what + " answered no valid JSON", 69);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new HostRefusal(69, what + " answered no JSON object");
        }
        return object(parsed);
    }

    void refusal(String words) throws IOException {
        json(ORG + "refused.json", fields("words", printable(words)));
    }

    static boolean printable(int c) {
        final int type = Character.getType(c);
        return c == ' '
                || type != Character.CONTROL
                        && type != Character.FORMAT
                        && type != Character.SURROGATE
                        && type != Character.PRIVATE_USE
                        && type != Character.UNASSIGNED
                        && type != Character.LINE_SEPARATOR
                        && type != Character.PARAGRAPH_SEPARATOR
                        && type != Character.SPACE_SEPARATOR;
    }

    static String printable(String text) {
        final StringBuilder answer = new StringBuilder();
        text.codePoints().forEach(c -> answer.appendCodePoint(printable(c) ? c : ' '));
        return answer.toString();
    }

    static String visible(String text) {
        final StringBuilder answer = new StringBuilder();
        text.codePoints().filter(FollowContext::printable).forEach(answer::appendCodePoint);
        return answer.toString();
    }

    static byte[] ascii(byte[] bytes, String label) {
        if (bytes.length > 4096) {
            throw new HostRefusal(65, "the " + label + " is too long");
        }
        for (byte b : bytes) {
            if (b < 0) {
                throw new HostRefusal(
                        65,
                        "the "
                                + label
                                + (label.equals("pin")
                                        ? " is not lines of a login and a key"
                                        : " is not one printable line"));
            }
        }
        return bytes;
    }

    void publish(Path destination, String text, List<String> readers) throws IOException {
        final Path staged = files.stage(destination, ProjectFiles.STAGED_PREFIX, text);
        try {
            if (!readers.isEmpty()) {
                command(
                        List.of(
                                "setfacl",
                                "--modify",
                                String.join(",", readers),
                                staged.toString()));
            }
            files.replace(staged, destination);
        } finally {
            files.delete(staged);
        }
    }
}
