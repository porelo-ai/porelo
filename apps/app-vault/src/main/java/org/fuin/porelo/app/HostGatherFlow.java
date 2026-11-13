package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/** Moves approved main between forges, leaving reconciliation to a task. */
final class HostGatherFlow {
    private final CommandRunner runner;
    private final Path git;
    private final Path curl;
    private final Path temporary;

    HostGatherFlow(CommandRunner runner, Path git, Path temporary) {
        this(runner, git, Path.of("/usr/bin/curl"), temporary);
    }

    HostGatherFlow(CommandRunner runner, Path git, Path curl, Path temporary) {
        this.runner = runner;
        this.git = git;
        this.curl = curl;
        this.temporary = temporary;
    }

    int run(
            boolean pull,
            HostGatherConfig config,
            List<String> names,
            char[] local,
            char[] central,
            PrintWriter out,
            PrintWriter err) {
        try {
            transfer(pull, config, names, local, central, out, err, (repository, stage) -> {});
            return 0;
        } catch (HostGatherRefusal ex) {
            if (!java.util.Objects.requireNonNull(
                            ex.getMessage(), "Gathered refusals have safe words")
                    .isBlank()) err.println("porelo: " + redact(ex.getMessage(), local, central));
            return ex.code();
        } catch (IOException ex) {
            err.println("porelo: no temporary directory could be made or cleaned");
            return 73;
        } finally {
            out.flush();
            err.flush();
        }
    }

    HostGatherPush.Result transfer(
            boolean pull,
            HostGatherConfig config,
            List<String> names,
            char[] local,
            char[] central,
            PrintWriter out,
            PrintWriter err,
            HostGatherPush.Progress progress)
            throws IOException {
        final List<HostGatherConfig.Repository> targets = config.select(names);
        final List<String> pushed = new ArrayList<>();
        final List<String> upToDate = new ArrayList<>();
        String repository = "";
        boolean writing = false;
        try (Workspace workspace = new Workspace(temporary, err, runner)) {
            config.refuseCredentials(central, local);
            if (!pull) {
                for (var target : targets) {
                    repository = target.name();
                    progress.stage(repository, "reading");
                    preflight(target, config.ca(), central);
                }
            }
            for (int index = 0; index < targets.size(); index++) {
                final var target = targets.get(index);
                repository = target.name();
                if (pull) progress.stage(repository, "reading");
                final String localUrl =
                        config.local() + "/" + config.owner() + "/" + target.name() + ".git";
                final Path repo = workspace.path.resolve(target.name());
                require(
                        target.name() + ": local main could not be read from " + localUrl,
                        request(
                                List.of(
                                        "clone",
                                        "--quiet",
                                        "--branch",
                                        "main",
                                        "--single-branch",
                                        "--",
                                        localUrl,
                                        repo.toString()),
                                localUrl,
                                local,
                                null),
                        local,
                        central);
                if (pull) {
                    pull(target, localUrl, repo, config.ca(), local, central, out);
                    continue;
                }
                final String head =
                        output(repo, List.of("rev-parse", "--short", "HEAD"), local, central);
                out.println(
                        redact(
                                target.name()
                                        + ": pushing local main "
                                        + head
                                        + " to "
                                        + target.central(),
                                local,
                                central));
                out.flush();
                progress.stage(repository, "pushing");
                writing = true;
                final var answer =
                        request(
                                in(
                                        repo,
                                        List.of(
                                                "push",
                                                "--porcelain",
                                                target.central(),
                                                "main:main")),
                                target.central(),
                                central,
                                config.ca());
                writing = false;
                if (answer.successful()) {
                    out.println(target.name() + ": taken");
                    final boolean unchanged =
                            answer.standardOutput()
                                    .lines()
                                    .anyMatch(
                                            line ->
                                                    line.startsWith("=\t")
                                                            && line.split("\t", -1).length == 3
                                                            && line.split("\t", -1)[1].equals(
                                                                    "refs/heads/main:refs/heads/main"));
                    if (unchanged) upToDate.add(repository);
                    else pushed.add(repository);
                    progress.stage(repository, unchanged ? "up-to-date" : "pushed");
                    continue;
                }
                final String said =
                        redact(answer.standardOutput() + answer.standardError(), local, central);
                final boolean unauthorized =
                        answer.standardError()
                                .lines()
                                .anyMatch(
                                        line ->
                                                line.matches(
                                                        "fatal: unable to access .+: The requested URL returned error: 401"));
                if (unauthorized) throw tokenRefused(target, 401);
                final boolean authentication =
                        answer.standardError()
                                .lines()
                                .anyMatch(
                                        line ->
                                                line.startsWith(
                                                        "fatal: Authentication failed for "));
                final boolean refused =
                        answer.standardOutput()
                                .lines()
                                .anyMatch(
                                        line ->
                                                line.startsWith("!\t")
                                                        && line.split("\t", -1).length == 3
                                                        && line.split("\t", -1)[1].equals(
                                                                "refs/heads/main:refs/heads/main"));
                final String left =
                        String.join(
                                " ",
                                targets.subList(index + 1, targets.size()).stream()
                                        .map(HostGatherConfig.Repository::name)
                                        .toList());
                if (authentication
                        || !said.contains("[rejected]")
                        || !(said.contains("fetch first") || said.contains("non-fast-forward")))
                    throw new HostGatherRefusal(
                            authentication
                                    ? HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED
                                    : refused
                                            ? HostGatherRefusal.Kind.PUSH
                                            : HostGatherRefusal.Kind.CANCELLED,
                            1,
                            target.name()
                                    + ": the push failed; not pushed: "
                                    + (left.isEmpty() ? "none" : left)
                                    + ". Git said: "
                                    + said,
                            Map.of("reason", "central-refused"));
                require(
                        target.name() + ": refused, and central main could not be read",
                        request(
                                in(repo, List.of("fetch", "--quiet", target.central(), "main")),
                                target.central(),
                                central,
                                config.ca()),
                        local,
                        central);
                final String missing =
                        output(
                                repo,
                                List.of("log", "--oneline", "main..FETCH_HEAD"),
                                local,
                                central);
                require(
                        target.name()
                                + ": refused, and central main could not be brought to the local forge",
                        request(
                                in(
                                        repo,
                                        List.of(
                                                "push",
                                                "--quiet",
                                                localUrl,
                                                "FETCH_HEAD:refs/heads/central/main")),
                                localUrl,
                                local,
                                null),
                        local,
                        central);
                err.println(
                        target.name()
                                + ": refused, since the central forge holds commits local main lacks:\n"
                                + missing);
                err.println(
                        target.name()
                                + ": they are on the local forge as the branch central/main; merge it in a task, approve it, push again.");
                throw new HostGatherRefusal(
                        HostGatherRefusal.Kind.PUSH,
                        1,
                        target.name()
                                + ": not pushed; not pushed either: "
                                + (left.isEmpty() ? "none" : left),
                        Map.of("reason", "reconcile", "branch", "central/main"));
            }
            if (!pull)
                out.println(
                        "pushed: "
                                + String.join(
                                        " ",
                                        targets.stream()
                                                .map(HostGatherConfig.Repository::name)
                                                .toList()));
            return new HostGatherPush.Result(pushed, upToDate);
        } catch (HostGatherRefusal ex) {
            final var failure =
                    writing && ex.kind() == HostGatherRefusal.Kind.HOST
                            ? new HostGatherRefusal(
                                    HostGatherRefusal.Kind.CANCELLED,
                                    ex.code(),
                                    String.valueOf(ex.getMessage()),
                                    Map.of())
                            : ex;
            throw failure.redacted(local, central)
                    .withDetails(
                            Map.of(
                                    "repository",
                                    repository,
                                    "pushed",
                                    List.copyOf(pushed),
                                    "upToDate",
                                    List.copyOf(upToDate)));
        } catch (RuntimeException ex) {
            throw new HostGatherRefusal(
                    writing ? HostGatherRefusal.Kind.CANCELLED : HostGatherRefusal.Kind.HOST,
                    70,
                    "the gathered transfer failed; inspect the forge before retrying",
                    Map.of(
                            "reason",
                            "unexpected",
                            "repository",
                            repository,
                            "pushed",
                            List.copyOf(pushed),
                            "upToDate",
                            List.copyOf(upToDate)));
        } catch (IOException ex) {
            throw new HostGatherRefusal(73, "no temporary directory could be made or cleaned")
                    .withDetails(
                            Map.of(
                                    "repository",
                                    repository,
                                    "pushed",
                                    List.copyOf(pushed),
                                    "upToDate",
                                    List.copyOf(upToDate)));
        } finally {
            out.flush();
            err.flush();
        }
    }

    private void preflight(HostGatherConfig.Repository target, Path ca, char[] token) {
        final byte[] input =
                ("header = \"Authorization: Basic " + basic(token) + "\"\n")
                        .getBytes(StandardCharsets.UTF_8);
        final CommandResult answer;
        try {
            answer =
                    runner.run(
                            Command.of(
                                            curl.toString(),
                                            "--disable",
                                            "--globoff",
                                            "--silent",
                                            "--fail",
                                            "--proto",
                                            "=https",
                                            "--proto-redir",
                                            "=https",
                                            "--max-redirs",
                                            "0",
                                            "--proxy",
                                            "",
                                            "--noproxy",
                                            "*",
                                            "--connect-timeout",
                                            "5",
                                            "--max-time",
                                            "15",
                                            "--max-filesize",
                                            "16777216",
                                            "--cacert",
                                            ca.toString(),
                                            "--config",
                                            "-",
                                            "--write-out",
                                            "\n%{http_code}",
                                            "--url",
                                            target.central().replaceAll("/+$", "")
                                                    + "/info/refs?service=git-receive-pack")
                                    .in(Path.of("/"))
                                    .withChosenEnvironment(
                                            Map.of("PATH", "/usr/sbin:/usr/bin", "LC_ALL", "C"))
                                    .withBinaryInput(input));
        } catch (CommandException ex) {
            throw HostGatherRefusal.host(
                    "transfer",
                    69,
                    target.name()
                            + ": central authentication could not be checked; inspect the forge connection");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        final String output = answer.standardOutput();
        final String status = output.substring(output.lastIndexOf('\n') + 1);
        if (status.equals("401")) throw tokenRefused(target, 401);
        if (!answer.successful()
                || !status.equals("200")
                || !output.startsWith("001f# service=git-receive-pack\n0000"))
            throw HostGatherRefusal.host(
                    "transfer",
                    1,
                    target.name()
                            + ": central authentication could not be checked at "
                            + forge(target)
                            + (status.matches("[1-5][0-9]{2}") ? " (HTTP " + status + ")" : "")
                            + "; inspect the central Git endpoint and retry; no repositories were pushed");
    }

    private static String forge(HostGatherConfig.Repository target) {
        return java.net.URI.create(target.central()).getAuthority();
    }

    private static HostGatherRefusal tokenRefused(HostGatherConfig.Repository target, int status) {
        return new HostGatherRefusal(
                HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED,
                1,
                target.name()
                        + ": central forge "
                        + forge(target)
                        + " refused authentication (HTTP "
                        + status
                        + ")",
                Map.of("forge", forge(target), "status", status));
    }

    private void pull(
            HostGatherConfig.Repository target,
            String localUrl,
            Path repo,
            Path ca,
            char[] local,
            char[] central,
            PrintWriter out) {
        require(
                target.name() + ": central main could not be read",
                request(
                        in(repo, List.of("fetch", "--quiet", target.central(), "main")),
                        target.central(),
                        central,
                        ca),
                local,
                central);
        if (output(repo, List.of("rev-parse", "HEAD"), local, central)
                .equals(output(repo, List.of("rev-parse", "FETCH_HEAD"), local, central))) {
            out.println(target.name() + ": local main is central main");
            return;
        }
        if (ancestor(repo, "FETCH_HEAD", "HEAD", local, central)) {
            out.println(
                    target.name()
                            + ": local main is ahead of central main; the gathered push sends it");
            return;
        }
        require(
                target.name() + ": central main could not be brought to the local forge",
                request(
                        in(
                                repo,
                                List.of(
                                        "push",
                                        "--quiet",
                                        localUrl,
                                        "+FETCH_HEAD:refs/heads/central/main")),
                        localUrl,
                        local,
                        null),
                local,
                central);
        if (ancestor(repo, "HEAD", "FETCH_HEAD", local, central)) {
            final String count =
                    output(
                            repo,
                            List.of("rev-list", "--count", "HEAD..FETCH_HEAD"),
                            local,
                            central);
            out.println(
                    target.name()
                            + ": central main is "
                            + count
                            + " commit"
                            + (count.equals("1") ? "" : "s")
                            + " ahead; it is on the local forge as central/main, for a pull request");
        } else
            out.println(
                    target.name()
                            + ": central main and local main went apart; central/main on the local forge is merged in a task");
    }

    private boolean ancestor(Path repo, String first, String second, char[] local, char[] central) {
        final var answer =
                request(
                        in(repo, List.of("merge-base", "--is-ancestor", first, second)),
                        null,
                        null,
                        null);
        if (answer.exitCode() != 0 && answer.exitCode() != 1)
            require("local and central commits could not be compared", answer, local, central);
        return answer.exitCode() == 0;
    }

    private String output(Path repo, List<String> arguments, char[] local, char[] central) {
        final var answer = request(in(repo, arguments), null, null, null);
        require("local and central commits could not be read", answer, local, central);
        return redact(answer.standardOutput().strip(), local, central);
    }

    private static List<String> in(Path repo, List<String> arguments) {
        final var argv = new ArrayList<>(List.of("-C", repo.toString()));
        argv.addAll(arguments);
        return argv;
    }

    private CommandResult request(
            List<String> arguments,
            @Nullable String url,
            char @Nullable [] token,
            @Nullable Path ca) {
        final var argv =
                new ArrayList<>(
                        List.of(
                                git.toString(),
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "gc.auto=0",
                                "-c",
                                "maintenance.auto=false",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "init.templateDir=",
                                "-c",
                                "http.followRedirects=false",
                                "-c",
                                "http.proxy=",
                                "-c",
                                "http.sslVerify=true"));
        argv.addAll(arguments);
        final Map<String, String> env =
                new LinkedHashMap<>(
                        Map.of(
                                "PATH",
                                "/usr/sbin:/usr/bin",
                                "LC_ALL",
                                "C",
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_SYSTEM",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_TERMINAL_PROMPT",
                                "0"));
        if (url != null && token != null) {
            final int slash = url.indexOf('/', url.indexOf("://") + 3);
            final String origin = slash < 0 ? url : url.substring(0, slash);
            env.putAll(
                    Map.of(
                            "GIT_CONFIG_COUNT",
                            "1",
                            "GIT_CONFIG_KEY_0",
                            "http." + origin + "/.extraHeader",
                            "GIT_CONFIG_VALUE_0",
                            "Authorization: Basic " + basic(token)));
        }
        if (ca != null) env.put("GIT_SSL_CAINFO", ca.toString());
        final Command command = Command.of(argv).in(Path.of("/")).withChosenEnvironment(env);
        try {
            return runner.run(command);
        } catch (CommandException ex) {
            throw new HostGatherRefusal(
                    1, "Git could not run; check the installed git and forge connection");
        }
    }

    private static void require(String reason, CommandResult answer, char[] local, char[] central) {
        if (!answer.successful())
            throw new HostGatherRefusal(
                    1,
                    reason
                            + ": "
                            + redact(
                                    answer.standardOutput() + answer.standardError(),
                                    local,
                                    central));
    }

    static String redact(String text, char[] first, char[] second) {
        String safe = text;
        for (char[] token : List.of(first, second))
            if (token.length > 0) {
                final String encoded = basic(token);
                safe =
                        safe.replace("Authorization: Basic " + encoded, "<redacted>")
                                .replace(encoded, "<redacted>")
                                .replace(new String(token), "<redacted>");
            }
        return safe.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "?");
    }

    private static String basic(char[] token) {
        final byte[] bytes =
                ("x-access-token:" + new String(token)).getBytes(StandardCharsets.UTF_8);
        try {
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class Workspace implements AutoCloseable {
        private final Path path;
        private final Thread hook;
        private boolean removed;

        Workspace(Path temporary, PrintWriter err, CommandRunner runner) throws IOException {
            path =
                    Files.createTempDirectory(
                            temporary,
                            "porelo-gather-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            hook =
                    new Thread(
                            () -> {
                                if (runner instanceof HostGatherRunner processes) processes.close();
                                try {
                                    remove();
                                } catch (IOException ex) {
                                    err.println("porelo: left " + path + " behind; remove it");
                                    err.flush();
                                }
                            },
                            "porelo-gather-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);
        }

        private synchronized void remove() throws IOException {
            if (removed) return;
            Files.walkFileTree(
                    path,
                    new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                                throws IOException {
                            Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(
                                Path directory, @Nullable IOException failure) throws IOException {
                            if (failure != null) throw failure;
                            Files.delete(directory);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
            removed = true;
        }

        @Override
        public void close() throws IOException {
            try {
                remove();
            } catch (IOException ex) {
                throw new HostGatherRefusal(73, "left " + path + " behind");
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ex) {
                    /* Shutdown already owns the hook and its cleanup. */
                }
            }
        }
    }
}
