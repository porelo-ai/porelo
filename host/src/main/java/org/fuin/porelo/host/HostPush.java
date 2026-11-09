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
    private final ProjectRuntime runtime;
    private final Clock clock;
    private final java.io.PrintWriter errors;

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
            request = PushRequest.read(input);
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
                PushJudgement.judge(
                        request.updates(),
                        request.field("pusher"),
                        login -> new HostRecords(files).read().containsKey(login),
                        login ->
                                tasks.read(login).stream()
                                        .filter(task -> project.equals(task.entry().get("project")))
                                        .map(HostTasks.Task::name)
                                        .collect(java.util.stream.Collectors.toSet()),
                        updates -> verify(project, request, account, entry));
        record(project, name, answer.entries());
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

    private HostPrograms.Result verify(
            String project, PushRequest request, String account, ProjectRuntime.Account entry)
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
                    "porelo-host-push-"
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
                            "RuntimeMaxSec=270",
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
                            "--json"));
            // The hook's refs are the only stdin the verifier receives.
            try {
                return runtime.run(
                        argv,
                        request.lines().getBytes(StandardCharsets.US_ASCII),
                        HostPrograms.ENVIRONMENT,
                        Duration.ofSeconds(280));
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
        if (entries.isEmpty()) return;
        final Path directory = files.path("/var/lib/porelo-host/arrivals/" + project);
        files.directories(directory);
        final Path path = directory.resolve(repository + ".jsonl");
        files.check(path, 73, false, false, 0600);
        try {
            Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            // Another accepted push can publish this same private ledger.
        }
        try (var lock = HostConfigurationLock.acquire(path, false);
                var sink =
                        FileChannel.open(
                                lock.descriptorPath(),
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND)) {
            files.checkOpened(lock.descriptorPath(), 0600);
            final ByteBuffer bytes =
                    StandardCharsets.UTF_8.encode(
                            entries.stream()
                                    .map(row -> ProjectRecords.pythonJson(row, true) + "\n")
                                    .collect(java.util.stream.Collectors.joining()));
            if (sink.size() > HostFiles.MAXIMUM_BYTES - (long) bytes.remaining())
                throw new HostRefusal(
                        73, "the arrival record would exceed the host's 16 MiB read limit");
            while (bytes.hasRemaining()) sink.write(bytes);
            sink.force(true);
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
