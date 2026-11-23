package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The installed push judge: checked host files, a confined verifier, and durable arrival records.
 */
final class HostPush {
    private static final String DATA = "/var/lib/gitea/";
    private static final String VOLUME =
            ".local/share/containers/storage/volumes/forgejo-data/_data/";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);
    private final HostFiles files;

    /** The commit check of a host push; its worker keeps 270 s of it. */
    private static final Duration COMMIT_CHECK = Duration.ofSeconds(280);

    private final ProjectRuntime runtime;
    private final Clock clock;
    private final java.io.PrintWriter errors;

    /**
     * Returns the time factors this run of porelo-host read from its host configuration.
     *
     * @return The factors the judge's limits follow.
     */
    org.fuin.porelo.wire.LimitFactors limits() {
        return runtime.limits();
    }

    HostPush(HostFiles files, ProjectRuntime runtime, Clock clock) {
        this(files, runtime, clock, new java.io.PrintWriter(System.err, true));
    }

    HostPush(HostFiles files, ProjectRuntime runtime, Clock clock, java.io.PrintWriter errors) {
        this.errors = errors;
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
    }

    void serve(InputStream input, OutputStream output, long peerUid) throws IOException {
        PushJudgement.Answer answer;
        try {
            final Path conf = files.path("/etc/porelo-host/forge.conf");
            files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, true)) {
                answer = judge(input, peerUid);
            }
        } catch (HostRefusal ex) {
            answer = PushJudgement.refusal(ex);
        } catch (IOException ex) {
            answer =
                    new PushJudgement.Answer(
                            500, "porelo: the host could not judge or record this push\n");
        }
        if (answer.status() == 500) {
            errors.print(answer.text());
            errors.flush();
        }
        try {
            output.write(answer.http());
            output.flush();
        } catch (IOException ex) {
            // The hook may have left after its own deadline, as in the predecessor.
        }
    }

    private PushJudgement.Answer judge(InputStream input, long peerUid) throws IOException {
        final List<String> settings =
                files.read("/etc/porelo-host/forge.conf")
                        .lines()
                        .filter(line -> line.startsWith("account="))
                        .map(line -> line.substring(8))
                        .toList();
        if (settings.size() != 1)
            throw new HostRefusal(69, "/etc/porelo-host/forge.conf names no single valid account");
        final String account = settings.getFirst();
        if (!account.matches("[a-z][a-z0-9-]{0,31}"))
            throw new HostRefusal(69, "forge.conf names no valid account");
        final var entry = runtime.required(account);
        if (peerUid != entry.uid())
            throw new HostRefusal(65, "only the forge's own hook asks for a push to be judged");
        final PushRequest request;
        try {
            request = PushRequest.read(input, runtime.limits());
        } catch (IOException ex) {
            throw new HostRefusal(69, "the hook's request could not be read");
        }
        final var repository =
                Pattern.compile(
                                Pattern.quote(DATA)
                                        + "git/repositories/("
                                        + HostRecords.PROJECT
                                        + ")/([a-z0-9._-]{1,100})\\.git")
                        .matcher(request.field("repository"));
        if (!repository.matches())
            throw new HostRefusal(65, "the hook names no repository of a project");
        final String project = repository.group(1);
        // PROJECT has a capturing group of its own.
        final String name = repository.group(3);
        registration(project);
        final var tasks = new HostTasks(files, runtime, clock);
        final var answer =
                PushJudgement.judgeAnswer(
                        request.updates(),
                        request.field("pusher"),
                        login -> new HostRecords(files).read().containsKey(login),
                        login ->
                                tasks.read(login).stream()
                                        .filter(task -> task.of(project))
                                        .map(HostTasks.Task::name)
                                        .collect(java.util.stream.Collectors.toSet()),
                        updates -> verify(project, name, request, account, entry));
        return answer;
    }

    private void registration(String project) {
        final Path directory = files.path("/etc/porelo-host/projects");
        files.check(directory, 69, false, true, 0700);
        final Path path = directory.resolve(project + ".conf");
        if (!files.check(path, 69, false, false, 0600))
            throw new HostRefusal(65, "project " + project + " is not registered on this host");
        ProjectRecords.pushRegistration(path, files.read(path), project);
    }

    Map<String, String> paths(
            Map<String, String> fields, String account, ProjectRuntime.Account entry) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("repository", "quarantine", "objects", "alternates"))
            values.put(
                    key,
                    java.util.Arrays.stream(
                                    java.util.Objects.requireNonNull(
                                                    fields.get(key),
                                                    "The parsed hook request includes every object path")
                                            .split("/", -1))
                            .filter(part -> !part.equals("."))
                            .collect(java.util.stream.Collectors.joining("/")));
        final String inside = values.get("repository");
        final String quarantine =
                java.util.Objects.requireNonNull(
                        values.get("quarantine"), "The quarantine was mapped above");
        if (!quarantine.matches(
                Pattern.quote(inside) + "/objects/(tmp_objdir-)?incoming-[A-Za-z0-9]{1,64}"))
            throw new HostRefusal(65, "the hook names no quarantine of this repository");
        if (!quarantine.equals(values.get("objects")))
            throw new HostRefusal(65, "the hook's object directory is not its quarantine");
        if (!(inside + "/objects").equals(values.get("alternates")))
            throw new HostRefusal(65, "the hook's alternate objects are not this repository's");
        final String home = files.path(entry.home()).toString();
        if (!home.matches("/[A-Za-z0-9._/-]{1,255}"))
            throw new HostRefusal(69, "the forge account's home cannot be named to a unit");
        final Set<Long> owners = new HashSet<>(Set.of(entry.uid()));
        for (String line : files.read("/etc/subuid").lines().toList()) {
            final String[] parts = line.split(":", -1);
            if (parts.length != 3
                    || !parts[0].matches("[a-zA-Z0-9_-]+")
                    || !parts[1].matches("[0-9]{1,10}")
                    || !parts[2].matches("[0-9]{1,10}")
                    || Long.parseLong(parts[2]) < 1
                    || Long.parseLong(parts[1]) + Long.parseLong(parts[2]) > 4294967295L)
                throw new HostRefusal(69, "subuid holds a malformed subordinate ID range");
            if (parts[0].equals(account) || parts[0].equals(Long.toString(entry.uid())))
                owners.add(Long.parseLong(parts[1]));
        }
        Path directory = Path.of(home);
        for (String part : (VOLUME + quarantine.substring(DATA.length())).split("/")) {
            directory = directory.resolve(part);
            final Map<String, Object> status;
            try {
                status =
                        Files.readAttributes(directory, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            } catch (IOException ex) {
                throw new HostRefusal(65, "the push's directory " + directory + " is missing");
            }
            if ((((Number)
                                                    java.util.Objects.requireNonNull(
                                                            status.get("mode"),
                                                            "Unix mode is present"))
                                            .intValue()
                                    & 0170000)
                            != 0040000
                    || !owners.contains(
                            Integer.toUnsignedLong(
                                    ((Number)
                                                    java.util.Objects.requireNonNull(
                                                            status.get("uid"),
                                                            "Unix uid is present"))
                                            .intValue())))
                throw new HostRefusal(
                        65,
                        "the push's directory " + directory + " is no directory of the forge's");
        }
        values.replaceAll((key, value) -> home + "/" + VOLUME + value.substring(DATA.length()));
        return Map.copyOf(values);
    }

    private PushJudgement.Answer verify(
            String project,
            String repository,
            PushRequest request,
            String account,
            ProjectRuntime.Account entry)
            throws IOException {
        final var host = paths(request.fields(), account, entry);
        runtime.require("systemd-run", "porelo");
        final Map<String, byte[]> texts = new LinkedHashMap<>();
        files.check(
                files.path("/etc/porelo-host/signers/" + project + ".certifiers"),
                69,
                true,
                false,
                -1);
        texts.put(
                "signers",
                files.read("/etc/porelo-host/signers/" + project + ".allowed")
                        .getBytes(StandardCharsets.UTF_8));
        texts.put(
                "certifiers",
                files.read("/etc/porelo-host/signers/" + project + ".certifiers")
                        .getBytes(StandardCharsets.UTF_8));
        texts.put("certificates", certificates(project).getBytes(StandardCharsets.UTF_8));
        if (texts.values().stream().anyMatch(bytes -> bytes.length > PushRequest.MAXIMUM))
            throw new HostRefusal(
                    69, "the push's signers or certificates are larger than a unit's credential");
        final Path push = files.path("/run/porelo-host/push");
        files.directories(push);
        files.check(push, 73, true, true, 0700);
        final Path directory;
        try {
            directory =
                    Files.createTempDirectory(
                            push,
                            "push.",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException ex) {
            throw new HostRefusal(
                    73, "cannot create a private push directory under /run/porelo-host/push");
        }
        try {
            for (var text : texts.entrySet())
                write(directory.resolve(text.getKey()), text.getValue());
            final byte[] krl = files.readBytes(files.path("/etc/ssh/porelo-revoked.krl"));
            if (krl.length > PushRequest.MAXIMUM)
                throw new HostRefusal(
                        69,
                        files.path("/etc/ssh/porelo-revoked.krl")
                                + " is larger than a unit's credential");
            try {
                write(directory.resolve("revoked.krl"), krl);
            } catch (HostRefusal ex) {
                throw new HostRefusal(73, "cannot stage the push's KRL in /run/porelo-host/push");
            }
            final String unit =
                    "porelo-host-verify-"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            final String credentials = "/run/credentials/" + unit + ".service/";
            final List<String> argv =
                    new ArrayList<>(
                            List.of(
                                    runtime.personProgram("systemd-run").toString(),
                                    "--pipe",
                                    "--wait",
                                    "--quiet",
                                    "--collect",
                                    "--unit=" + unit));
            for (String property :
                    List.of(
                            "User=" + account,
                            "PrivateNetwork=yes",
                            "PrivateTmp=yes",
                            "ProtectHome=tmpfs",
                            "ProtectSystem=strict",
                            "NoNewPrivileges=yes",
                            "RuntimeMaxSec="
                                    + HostLimits.span(
                                            runtime.limits().machines(Duration.ofSeconds(270))),
                            "BindReadOnlyPaths=" + host.get("repository"),
                            "Environment=HOME=/tmp",
                            "Environment=GIT_QUARANTINE_PATH=" + host.get("quarantine"),
                            "Environment=GIT_OBJECT_DIRECTORY=" + host.get("objects"),
                            "Environment=GIT_ALTERNATE_OBJECT_DIRECTORIES="
                                    + host.get("alternates"))) argv.addAll(List.of("-p", property));
            for (String name : List.of("signers", "certifiers", "certificates", "revoked.krl"))
                argv.addAll(
                        List.of("-p", "LoadCredential=" + name + ":" + directory.resolve(name)));
            argv.addAll(
                    List.of(
                            runtime.personProgram("porelo").toString(),
                            "commit",
                            "verify",
                            "--repository",
                            host.get("repository"),
                            "--signers",
                            credentials + "signers",
                            "--certifiers",
                            credentials + "certifiers",
                            "--krl",
                            credentials + "revoked.krl",
                            "--certificates",
                            credentials + "certificates",
                            "--json-lines"));
            // The hook's refs are the only stdin the verifier receives.
            try {
                final Duration timeout = runtime.limits().machines(COMMIT_CHECK);
                // The verdicts grow with the push, so they are staged on disk, never in /run.
                final Path staged = stagingDirectory(timeout);
                try (var verdicts =
                        new PushVerdicts(staged, PushJudgement.refs(request.updates()), timeout)) {
                    final var result =
                            runtime.stream(
                                    argv,
                                    new java.io.ByteArrayInputStream(
                                            request.lines().getBytes(StandardCharsets.US_ASCII)),
                                    verdicts,
                                    HostPrograms.ENVIRONMENT,
                                    timeout);
                    // Its deadline starts anew for recording, so that a concurrent judgement's
                    // sweep leaves it alone while it is read.
                    Files.setLastModifiedTime(
                            staged, java.nio.file.attribute.FileTime.from(clock.instant()));
                    final var answer = verdicts.finish(result);
                    if (answer.status() == 200 && Files.size(verdicts.entries()) > 0)
                        record(project, repository, verdicts.entries(), verdicts.checkedAt());
                    return answer;
                } finally {
                    removeStaged(staged);
                }
            } catch (IOException ex) {
                throw new HostRefusal(69, "porelo commit verify could not run");
            }
        } finally {
            try {
                files.removeTree(directory);
            } catch (IOException | HostRefusal ex) {
                // The predecessor's cleanup is best effort; these private files contain only public
                // trust material.
            }
        }
    }

    /**
     * Makes a private folder for one judgement's verdicts, first removing every one a killed
     * porelo-host left: older than a judgement's deadline, so a concurrent judgement's stays.
     */
    private Path stagingDirectory(Duration deadline) throws IOException {
        final Path root = files.path("/var/lib/porelo-host/push");
        files.directories(root);
        files.check(root, 73, true, true, 0700);
        final Instant stale = clock.instant().minus(deadline);
        for (Path each : files.list(root, 73, 0700)) {
            if (!each.getFileName().toString().startsWith("verdicts.")) continue;
            final Instant changed;
            try {
                changed = Files.getLastModifiedTime(each, LinkOption.NOFOLLOW_LINKS).toInstant();
            } catch (IOException ex) {
                // Gone meanwhile: its own judgement removed it.
                continue;
            }
            if (changed.isBefore(stale)) removeStaged(each);
        }
        try {
            return Files.createTempDirectory(
                    root,
                    "verdicts.",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (IOException ex) {
            throw new HostRefusal(
                    73,
                    "cannot create a private verdict directory under /var/lib/porelo-host/push");
        }
    }

    /** Removes staged verdicts, saying in porelo-host's log a folder that stays. */
    private void removeStaged(Path staged) {
        try {
            files.removeTree(staged);
        } catch (IOException | HostRefusal ex) {
            errors.println(
                    "porelo-host: the staged verdicts " + staged + " stay: " + ex.getMessage());
            errors.flush();
        }
    }

    String certificates(String project) {
        final StringBuilder text = new StringBuilder();
        final Instant now = clock.instant();
        for (var task : new HostTasks(files, runtime, clock).read()) {
            final var item = task.entry();
            try {
                final Instant after = time(string(item.get("valid_after")));
                final Instant before = time(string(item.get("valid_before")));
                final var principals = array(item.get("principals"));
                if (!now.isBefore(after)
                        && now.isBefore(before)
                        && !principals.isEmpty()
                        && principals.stream()
                                .allMatch(
                                        value ->
                                                value instanceof String principal
                                                        && principal.startsWith(
                                                                "task:" + project + ":")))
                    text.append(ProjectRecords.pythonJson(item, true)).append('\n');
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
                // A record without two readable dates is not among the worker's current
                // certificates.
            }
        }
        return text.toString();
    }

    private static Instant time(String text) {
        return LocalDateTime.parse(
                        text.endsWith("Z") ? text.substring(0, text.length() - 1) : text, TIME)
                .toInstant(ZoneOffset.UTC);
    }

    void record(String project, String repository, List<Map<String, @Nullable Object>> entries)
            throws IOException {
        record(project, repository, entries, true);
    }

    void recordArming(
            String project, String repository, List<Map<String, @Nullable Object>> entries)
            throws IOException {
        record(project, repository, entries, false);
    }

    private void record(
            String project,
            String repository,
            List<Map<String, @Nullable Object>> entries,
            boolean publish)
            throws IOException {
        if (entries.isEmpty()) return;
        append(
                project,
                repository,
                publish,
                sink -> {
                    for (var row : entries) {
                        final ByteBuffer bytes =
                                StandardCharsets.UTF_8.encode(
                                        ProjectRecords.pythonJson(row, true) + "\n");
                        while (bytes.hasRemaining()) sink.write(bytes);
                    }
                });
    }

    private void record(String project, String repository, Path entries, String checked)
            throws IOException {
        append(
                project,
                repository,
                true,
                sink -> {
                    try (var input = Files.newBufferedReader(entries)) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            final var row = object(parse(line));
                            row.put("arrived", checked);
                            final ByteBuffer bytes =
                                    StandardCharsets.UTF_8.encode(
                                            ProjectRecords.pythonJson(row, true) + "\n");
                            while (bytes.hasRemaining()) sink.write(bytes);
                        }
                    }
                });
    }

    @FunctionalInterface
    private interface Append {
        void write(FileChannel sink) throws IOException;
    }

    private void append(String project, String repository, boolean publish, Append entries)
            throws IOException {
        final Path directory = files.path("/var/lib/porelo-host/arrivals/" + project);
        files.directories(directory);
        final Path path = directory.resolve(repository + ".jsonl");
        while (true) {
            files.check(path, 73, false, false, 0600);
            try {
                Files.createFile(
                        path,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                // Another accepted push can publish this same private ledger.
            }
            final HostConfigurationLock lock;
            try {
                lock = HostConfigurationLock.acquire(path, false);
            } catch (IOException ex) {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                throw ex;
            }
            try (lock;
                    var sink =
                            FileChannel.open(
                                    lock.descriptorPath(),
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.APPEND)) {
                files.checkOpened(lock.descriptorPath(), 0600);
                try {
                    // A refused first append can unlink an inode another writer was waiting on.
                    if (!Files.isSameFile(path, lock.descriptorPath())) continue;
                } catch (java.nio.file.NoSuchFileException ex) {
                    continue;
                }
                final long previous = sink.size();
                final var publication = new ProjectArrivals(files.projects(), runtime, clock);
                try {
                    if (publish) publication.remove(project, repository);
                    entries.write(sink);
                    sink.force(true);
                    if (publish) publication.publish(project, repository, path);
                } catch (IOException | RuntimeException | Error ex) {
                    // A refused publication must not leave an accepted row for the refused push.
                    sink.truncate(previous);
                    sink.force(true);
                    if (previous == 0) Files.deleteIfExists(path);
                    throw ex;
                }
                return;
            }
        }
    }

    private static void write(Path path, byte[] bytes) {
        try (var sink =
                FileChannel.open(
                        path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")))) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) sink.write(buffer);
            sink.force(true);
        } catch (IOException ex) {
            throw new HostRefusal(73, "cannot write " + path);
        }
    }
}
