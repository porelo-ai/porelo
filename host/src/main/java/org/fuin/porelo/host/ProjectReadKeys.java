package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;

/** Read-key manifests and the full-reference checks that precede project destruction. */
final class ProjectReadKeys {
    private final ProjectFiles files;

    /** A mirror clone or a fetch of main from central. */
    private static final Duration CENTRAL_TRANSFER = Duration.ofSeconds(120);

    /** {@code git rev-list --all} on the mirror. */
    private static final Duration INVENTORY = Duration.ofSeconds(120);

    /** The ssh connect to central, given to ssh in whole seconds. */
    private static final Duration CONNECT = Duration.ofSeconds(10);

    /** {@code git ls-remote} of central's main. */
    private static final Duration LS_REMOTE = Duration.ofSeconds(30);

    /** A local git step on the scratch mirror: init, rev-parse. */
    private static final Duration LOCAL_GIT = Duration.ofSeconds(30);

    /** The fetch of the local copy bundle. */
    private static final Duration BUNDLE_FETCH = Duration.ofSeconds(60);

    /** The bundle of a follow. */
    private static final Duration BUNDLE = Duration.ofSeconds(60);

    private final ProjectRuntime runtime;

    ProjectReadKeys(ProjectFiles files, ProjectRuntime runtime) {
        this.files = files;
        this.runtime = runtime;
    }

    record Manifest(Path directory, List<Map<String, @Nullable Object>> entries) {
        @Nullable Map<String, @Nullable Object> find(String name, boolean ignoreCase) {
            return entries.stream()
                    .filter(
                            item ->
                                    ignoreCase
                                            ? name.equalsIgnoreCase(name(item))
                                            : name.equals(name(item)))
                    .findFirst()
                    .orElse(null);
        }
    }

    Manifest manifest(String project) throws IOException {
        final Path base = files.etc.resolve("organization/read");
        final Path directory = base.resolve("projects/" + project);
        for (Path path : List.of(base, base.resolve("projects"), directory)) {
            files.directory(ProjectFiles.parent(path), false, false);
            if (!files.exists(path)) {
                return new Manifest(directory, new ArrayList<>());
            }
            files.directory(path, true, false);
        }
        final Path path = directory.resolve("repositories.json");
        if (!files.exists(path)) {
            return new Manifest(directory, new ArrayList<>());
        }
        final Object parsed =
                ProjectRecords.json(
                        files.text(path, true),
                        "the project's read-key manifest does not read",
                        65);
        if (!(parsed instanceof List<?> items)) {
            throw malformed();
        }
        final List<Map<String, @Nullable Object>> entries = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> value)
                    || !(value.get("name") instanceof String name)
                    || !name.matches(ProjectRecords.REPOSITORY)
                    || !names.add(name)
                    || !(value.get("address") instanceof String address)
                    || !(value.get("key_id") instanceof Long id)
                    || id < 0
                    || value.containsKey("title") && !(value.get("title") instanceof String)) {
                throw malformed();
            }
            ProjectAddress.parse(address);
            final Map<String, @Nullable Object> copy = new LinkedHashMap<>();
            value.forEach((key, entry) -> copy.put((String) key, entry));
            entries.add(copy);
        }
        return new Manifest(directory, entries);
    }

    void writeManifest(Manifest manifest) throws IOException {
        files.publish(
                manifest.directory().resolve("repositories.json"),
                ".repositories-",
                ProjectRecords.pythonJson(manifest.entries(), false) + "\n");
    }

    List<Path> paths(String project) throws IOException {
        final Manifest manifest = manifest(project);
        if (!files.exists(manifest.directory())) {
            return List.of();
        }
        final Set<String> expected = new HashSet<>(Set.of("repositories.json"));
        for (Map<String, @Nullable Object> item : manifest.entries()) {
            expected.add(lower(name(item)));
            expected.add(lower(name(item)) + ".pub");
        }
        final List<Path> paths = files.list(manifest.directory());
        for (Path path : paths) {
            final String name = path.getFileName().toString();
            if (!expected.contains(name) && !name.startsWith(".repositories-")) {
                throw new HostRefusal(
                        73, "the project's read directory holds an unknown file; keeping it");
            }
            files.trusted(path, false);
        }
        return paths;
    }

    void drop(String project) throws IOException {
        final Manifest manifest = manifest(project);
        final List<Path> paths = new ArrayList<>(paths(project));
        paths.sort(
                java.util.Comparator.comparing(
                        path -> path.getFileName().toString().equals("repositories.json")));
        for (Path path : paths) {
            files.unlink(path);
        }
        if (files.exists(manifest.directory())) {
            files.unlink(manifest.directory());
        }
    }

    List<Map<String, @Nullable Object>> left(String project) throws IOException {
        return manifest(project).entries().stream()
                .filter(item -> id(item) > 0)
                .map(
                        item ->
                                ProjectRecords.object(
                                        "address",
                                        address(item),
                                        "title",
                                        item.getOrDefault("title", "porelo-host-" + project)))
                .toList();
    }

    void check(String account, String project, List<Map<?, ?>> repositories) throws IOException {
        final Manifest manifest = manifest(project);
        final Map<String, @Nullable Object> missing = new LinkedHashMap<>();
        for (Map<?, ?> repository : repositories) {
            final String name = name(repository);
            final Map<String, @Nullable Object> item = manifest.find(name, false);
            if (item == null
                    || id(item) <= 0
                    || !ProjectAddress.parse(address(item))
                            .https()
                            .equals(repository.get("original_url"))) {
                throw new HostRefusal(
                        69,
                        project
                                + "/"
                                + name
                                + " has no confirmed read key for its central address");
            }
            final HostPrograms.Result local = runtime.inventory(account, project, name);
            if (local.exitCode() != 0) {
                throw new HostRefusal(
                        69,
                        "the local forge could not list all commits of " + project + "/" + name);
            }
            final Set<String> commits = commits(local.output());
            commits.removeAll(centralCommits(manifest.directory(), item));
            if (!commits.isEmpty()) {
                missing.put(name, List.copyOf(commits));
            }
        }
        if (!missing.isEmpty()) {
            throw new Unpushed(
                    project + " has unpushed commits in " + String.join(", ", missing.keySet()),
                    missing);
        }
    }

    static final class Unpushed extends RuntimeException {
        final Map<String, @Nullable Object> commits;

        Unpushed(String message, Map<String, @Nullable Object> commits) {
            super(message);
            this.commits = commits;
        }
    }

    Set<String> centralCommits(Path directory, Map<String, @Nullable Object> item)
            throws IOException {
        final String ssh = ssh(directory, item);
        final Path scratch =
                Files.createTempDirectory(
                        files.at("/tmp"),
                        "porelo-project-read-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        try {
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwx------"));
            final Path mirror = scratch.resolve("central.git");
            git(
                    List.of(
                            "clone",
                            "--mirror",
                            "--",
                            ProjectAddress.parse(address(item)).ssh(),
                            mirror.toString()),
                    Map.of("GIT_SSH_COMMAND", ssh),
                    runtime.limits().services(CENTRAL_TRANSFER));
            return commits(
                    git(
                            List.of("--git-dir=" + mirror, "rev-list", "--all"),
                            Map.of(),
                            runtime.limits().machines(INVENTORY)));
        } finally {
            files.removeTree(scratch);
        }
    }

    String ssh(Path directory, Map<String, @Nullable Object> item) throws IOException {
        final Path key = directory.resolve(lower(name(item)));
        final Path known = files.etc.resolve("organization/known_hosts");
        files.trusted(key, true);
        files.trusted(known, true);
        if (files.root.equals(Path.of("/"))) {
            SystemProgram.at(Path.of("/usr/bin/ssh"));
        }
        return List.of(
                        "/usr/bin/ssh",
                        "-F",
                        "/dev/null",
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "IdentitiesOnly=yes",
                        "-o",
                        "ConnectTimeout=" + HostLimits.seconds(runtime.limits().services(CONNECT)),
                        "-o",
                        "IdentityAgent=none",
                        "-o",
                        "StrictHostKeyChecking=yes",
                        "-o",
                        "UpdateHostKeys=no",
                        "-o",
                        "GlobalKnownHostsFile=/dev/null",
                        "-o",
                        "UserKnownHostsFile=" + known,
                        "-i",
                        key.toString())
                .stream()
                .map(ProjectReadKeys::quote)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quote(String value) {
        return value.matches("[A-Za-z0-9_@%+=:,./-]+")
                ? value
                : "'" + value.replace("'", "'\"'\"'") + "'";
    }

    String git(List<String> arguments, Map<String, String> extra, Duration timeout)
            throws IOException {
        final Map<String, String> environment = new LinkedHashMap<>(ProjectRuntime.SAFE_ENV);
        environment.putAll(
                Map.of(
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_SYSTEM",
                        "/dev/null",
                        "GIT_TERMINAL_PROMPT",
                        "0"));
        environment.putAll(extra);
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                runtime.checked("/usr/bin/git").toString(),
                                "-c",
                                "core.hooksPath=/dev/null"));
        command.addAll(arguments);
        final HostPrograms.Result result =
                arguments.getFirst().equals("clone") || arguments.getFirst().equals("init")
                        ? runtime.privateFiles(command, new byte[0], environment, timeout)
                        : arguments.contains("rev-list") && !arguments.contains("--count")
                                ? runtime.collect(command, environment, timeout)
                                : runtime.run(command, new byte[0], environment, timeout);
        if (result.exitCode() != 0) {
            throw new HostRefusal(
                    69,
                    "the project read-only Git command failed (exit " + result.exitCode() + ")");
        }
        return result.output();
    }

    @Nullable String centralMain(Path directory, Map<String, @Nullable Object> item)
            throws IOException {
        final String text =
                git(
                        List.of(
                                "ls-remote",
                                "--",
                                ProjectAddress.parse(address(item)).ssh(),
                                "refs/heads/main"),
                        Map.of("GIT_SSH_COMMAND", ssh(directory, item)),
                        runtime.limits().services(LS_REMOTE));
        if (text.isBlank()) {
            return null;
        }
        final String commit = text.split("\t", 2)[0];
        if (!commit.matches(ProjectRecords.COMMIT)) {
            throw new HostRefusal(69, "the central forge answered an invalid main");
        }
        return commit;
    }

    Map<String, @Nullable Object> centralState(String account, String project) throws IOException {
        final Manifest manifest = manifest(project);
        final List<Map<String, @Nullable Object>> answers = new ArrayList<>();
        final Set<String> known = new HashSet<>();
        for (Map<String, @Nullable Object> item : manifest.entries()) {
            final Map<String, @Nullable Object> row =
                    ProjectRecords.object("repository", name(item));
            row.putAll(state(account, project, manifest.directory(), item));
            answers.add(row);
            known.add(lower(name(item)));
        }
        final String folder = "/var/lib/gitea/git/repositories/" + project;
        if (runtime.exec(account, List.of("test", "-d", folder)).exitCode() == 0) {
            final HostPrograms.Result listed = runtime.exec(account, List.of("ls", folder));
            if (listed.exitCode() != 0) {
                throw new HostRefusal(
                        69, "the local forge could not list the repositories of " + project);
            }
            for (String entry : listed.output().lines().toList()) {
                final String name =
                        entry.endsWith(".git") ? entry.substring(0, entry.length() - 4) : entry;
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                    throw new HostRefusal(
                            69, "the local forge holds an invalid repository name for " + project);
                }
                if (!known.contains(name)) {
                    answers.add(
                            ProjectRecords.object(
                                    "repository",
                                    name,
                                    "state",
                                    "cannot-say",
                                    "why",
                                    "no-read-key"));
                }
            }
        }
        return ProjectRecords.object("project", project, "repositories", answers);
    }

    /**
     * Brings central's main into a root-owned scratch repository with the read key, seeded with the
     * copy's main so only what the copy lacks travels, and lands it in the copy by {@link
     * ProjectAdvance#follow}, which moves main only by a fast-forward that changes no workflow.
     */
    void follow(String account, String project, String repository) throws IOException {
        final Manifest manifest = manifest(project);
        follow(
                account,
                project,
                manifest.directory(),
                ProjectCopies.requireKey(manifest, repository));
    }

    /** One repository's row measured again, as {@link #centralState} measures it. */
    Map<String, @Nullable Object> row(String account, String project, String repository)
            throws IOException {
        final Manifest manifest = manifest(project);
        return state(
                account,
                project,
                manifest.directory(),
                ProjectCopies.requireKey(manifest, repository));
    }

    private void follow(
            String account, String project, Path directory, Map<String, @Nullable Object> item)
            throws IOException {
        final String ssh = ssh(directory, item);
        final Path scratch =
                Files.createTempDirectory(
                        files.at("/tmp"),
                        "porelo-project-follow-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        try {
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwx------"));
            final Path mirror = scratch.resolve("central.git");
            final Path seed = scratch.resolve("copy.bundle");
            final Path out = scratch.resolve("central.bundle");
            git(
                    List.of("init", "-q", "--bare", "--", mirror.toString()),
                    Map.of(),
                    runtime.limits().machines(LOCAL_GIT));
            final String at = "--git-dir=" + mirror;
            Files.createFile(
                    seed,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            try (var output =
                    Files.newOutputStream(
                            seed,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                runtime.exportMain(account, project, name(item), output);
            }
            git(
                    List.of(
                            "-c",
                            "transfer.fsckObjects=true",
                            at,
                            "fetch",
                            "-q",
                            "--no-tags",
                            "--no-recurse-submodules",
                            "--",
                            seed.toString(),
                            "+refs/heads/main:refs/porelo/copy"),
                    Map.of(),
                    runtime.limits().services(BUNDLE_FETCH));
            git(
                    List.of(
                            "-c",
                            "transfer.fsckObjects=true",
                            at,
                            "fetch",
                            "-q",
                            "--no-tags",
                            "--no-recurse-submodules",
                            "--",
                            ProjectAddress.parse(address(item)).ssh(),
                            "+refs/heads/main:refs/heads/main"),
                    Map.of("GIT_SSH_COMMAND", ssh),
                    runtime.limits().services(CENTRAL_TRANSFER));
            final String target =
                    git(
                                    List.of(
                                            at,
                                            "rev-parse",
                                            "--verify",
                                            "--quiet",
                                            "refs/heads/main^{commit}"),
                                    Map.of(),
                                    runtime.limits().machines(LOCAL_GIT))
                            .strip();
            if (!target.matches(ProjectRecords.COMMIT)) {
                throw new HostRefusal(69, "the central forge answered an invalid main");
            }
            // Only what the copy lacks goes into the bundle; the copy holds the rest.
            git(
                    List.of(
                            at,
                            "bundle",
                            "create",
                            "-q",
                            out.toString(),
                            "refs/heads/main",
                            "^refs/porelo/copy"),
                    Map.of(),
                    runtime.limits().machines(BUNDLE));
            new ProjectAdvance(files, runtime, account, project, name(item)).follow(target, out);
        } finally {
            files.removeTree(scratch);
        }
    }

    private Map<String, @Nullable Object> state(
            String account, String project, Path directory, Map<String, @Nullable Object> item) {
        if (id(item) <= 0) {
            return unknown("no-read-key");
        }
        final @Nullable String central;
        try {
            central = centralMain(directory, item);
            if (central == null) {
                return unknown("no-central-main");
            }
        } catch (HostRefusal | IOException ex) {
            return unknown("unreachable");
        }
        try {
            final HostPrograms.Result local =
                    runtime.read(
                            account,
                            project,
                            name(item),
                            "rev-parse",
                            "--verify",
                            "--quiet",
                            "refs/heads/main^{commit}");
            if (local.exitCode() != 0) {
                return unknown("local");
            }
            if (local.output().strip().equals(central)) {
                return ProjectRecords.object("state", "up-to-date");
            }
            final int below =
                    runtime.read(
                                    account,
                                    project,
                                    name(item),
                                    "merge-base",
                                    "--is-ancestor",
                                    central,
                                    "refs/heads/main")
                            .exitCode();
            if (below != 0) {
                // Diverged only where the copy holds central's commit and neither holds the
                // other; without central's objects the copy is taken as behind.
                return ProjectRecords.object(
                        "state",
                        below == 1
                                        && runtime.read(
                                                                account,
                                                                project,
                                                                name(item),
                                                                "merge-base",
                                                                "--is-ancestor",
                                                                "refs/heads/main",
                                                                central)
                                                        .exitCode()
                                                == 1
                                ? "diverged"
                                : "moved-on");
            }
            final HostPrograms.Result count =
                    runtime.read(
                            account,
                            project,
                            name(item),
                            "rev-list",
                            "--count",
                            central + "..refs/heads/main");
            if (count.exitCode() != 0 || !count.output().strip().matches("[0-9]+")) {
                return unknown("local");
            }
            return ProjectRecords.object(
                    "state", "ahead", "commits", Long.parseLong(count.output().strip()));
        } catch (HostRefusal | IOException | NumberFormatException ex) {
            return unknown("local");
        }
    }

    private static Map<String, @Nullable Object> unknown(String why) {
        return ProjectRecords.object("state", "cannot-say", "why", why);
    }

    static String name(Map<?, ?> item) {
        return (String)
                java.util.Objects.requireNonNull(item.get("name"), "Validated manifest has a name");
    }

    static String address(Map<?, ?> item) {
        return (String)
                java.util.Objects.requireNonNull(
                        item.get("address"), "Validated manifest has an address");
    }

    static long id(Map<?, ?> item) {
        return (Long)
                java.util.Objects.requireNonNull(
                        item.get("key_id"), "Validated manifest has a key id");
    }

    static String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    static Set<String> commits(String text) {
        final Set<String> commits = new TreeSet<>();
        for (String line : text.lines().toList()) {
            if (!line.matches(ProjectRecords.COMMIT)) {
                throw new HostRefusal(69, "Git answered an invalid commit set");
            }
            commits.add(line);
        }
        return commits;
    }

    private static HostRefusal malformed() {
        return new HostRefusal(65, "the project's read-key manifest is malformed");
    }
}
