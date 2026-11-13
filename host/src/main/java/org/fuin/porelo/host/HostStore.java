package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Root's credential files and the ordered copies into and out of the project's store. */
final class HostStore {
    private static final String STORE = "/etc/porelo-host/store";
    private static final List<String> KEYS = List.of("repository", "password", "s3-credentials");
    private static final int MISSING_REPOSITORY = 10;
    private final HostFiles files;
    private final ProjectRuntime runtime;
    private final InputStream input;

    HostStore(HostFiles files, ProjectRuntime runtime, InputStream input) {
        this.files = files;
        this.runtime = runtime;
        this.input = input;
    }

    static CommandLine commandLine(HostFiles files, ProjectRuntime runtime, InputStream input) {
        final HostStore store = new HostStore(files, runtime, input);
        return new CommandLine(new PoreloHost.Group())
                .addSubcommand("put", new Put(store))
                .addSubcommand("keep", new Keep(store))
                .addSubcommand("restore", new Restore(store));
    }

    private HostConfigurationLock lock() throws IOException {
        final Path lock = files.path("/etc/porelo-host/store.lock");
        final Path parent =
                java.util.Objects.requireNonNull(lock.getParent(), "Store lock has a parent");
        if (!files.check(parent, 73, false, true, -1)) files.directories(parent);
        try {
            Files.createFile(
                    lock,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (FileAlreadyExistsException ex) {
            // A concurrent store operation may have created the shared lock first.
        }
        files.check(lock, 73, true, false, 0600);
        return HostConfigurationLock.acquire(lock, false);
    }

    private void put(PrintWriter out) throws IOException {
        final Map<String, byte[]> values = readInput();
        try (final var lock = lock()) {
            final Path directory = files.path(STORE);
            files.directories(directory);
            for (final String name : KEYS)
                files.check(directory.resolve(name), 73, false, false, 0600);
            publish(directory, values);
            for (final String name : KEYS) out.println(name);
        } finally {
            values.values().forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    private Map<String, byte[]> readInput() throws IOException {
        final Map<String, byte[]> values = new LinkedHashMap<>();
        final byte[] bytes = input.readNBytes(HostFiles.MAXIMUM_BYTES + 1);
        try {
            if (bytes.length > HostFiles.MAXIMUM_BYTES) throw invalidInput();
            final String text = new String(bytes, StandardCharsets.US_ASCII);
            for (final String line : text.lines().toList()) {
                final String[] parts = line.split(" ", -1);
                if (parts.length != 3
                        || !parts[0].equals("store")
                        || !KEYS.contains(parts[1])
                        || values.containsKey(parts[1])
                        || parts[2].isEmpty()) throw invalidInput();
                try {
                    values.put(parts[1], Base64.getDecoder().decode(parts[2]));
                } catch (IllegalArgumentException ex) {
                    throw invalidInput();
                }
            }
            if (values.size() != KEYS.size()) throw invalidInput();
            return values;
        } catch (RuntimeException ex) {
            values.values().forEach(value -> Arrays.fill(value, (byte) 0));
            throw ex;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static HostRefusal invalidInput() {
        return new HostRefusal(
                65,
                "store put requires exactly one base64 line each for repository, password and"
                        + " s3-credentials");
    }

    private void publish(Path directory, Map<String, byte[]> values) throws IOException {
        final Map<Path, Path> staged = new LinkedHashMap<>();
        final Map<Path, Path> previous = new LinkedHashMap<>();
        final List<Path> published = new ArrayList<>();
        try {
            for (final String name : KEYS) {
                final Path destination = directory.resolve(name);
                if (files.check(destination, 73, false, false, 0600)) {
                    final byte[] old = files.readBytes(destination);
                    try {
                        previous.put(destination, files.stagePrivate(destination, old));
                    } finally {
                        Arrays.fill(old, (byte) 0);
                    }
                }
                staged.put(
                        destination,
                        files.stagePrivate(
                                destination,
                                java.util.Objects.requireNonNull(
                                        values.get(name), "Every store key was validated")));
            }
            for (final var entry : staged.entrySet()) {
                Files.move(
                        entry.getValue(),
                        entry.getKey(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                published.add(entry.getKey());
            }
        } catch (IOException | RuntimeException ex) {
            for (final Path destination : published.reversed()) {
                try {
                    final Path old = previous.get(destination);
                    if (old == null) Files.delete(destination);
                    else
                        Files.move(
                                old,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException rollback) {
                    ex.addSuppressed(rollback);
                }
            }
            throw ex;
        } finally {
            for (final Path temporary : staged.values()) Files.deleteIfExists(temporary);
            for (final Path temporary : previous.values()) Files.deleteIfExists(temporary);
        }
    }

    private Map<String, String> environment() {
        files.check(files.path(STORE), 70, true, true, 0700);
        for (final String name : KEYS)
            files.check(files.path(STORE + "/" + name), 70, true, false, 0600);
        final Map<String, String> environment = new LinkedHashMap<>(HostPrograms.ENVIRONMENT);
        environment.put("RESTIC_REPOSITORY_FILE", files.path(STORE + "/repository").toString());
        environment.put("RESTIC_PASSWORD_FILE", files.path(STORE + "/password").toString());
        environment.put(
                "AWS_SHARED_CREDENTIALS_FILE", files.path(STORE + "/s3-credentials").toString());
        return Map.copyOf(environment);
    }

    private int keep(boolean all, PrintWriter out) throws IOException {
        try (final var lock = lock()) {
            final Map<String, String> environment = environment();
            final List<StoreEntries.Entry> entries =
                    StoreEntries.due(StoreEntries.read(files), all);
            if (!repository(environment))
                checked(
                        "store",
                        "restic init",
                        restic("store", List.of("init"), environment),
                        false);
            for (final StoreEntries.Entry entry : entries) keep(entry, environment, out);
        }
        return 0;
    }

    private void keep(StoreEntries.Entry entry, Map<String, String> environment, PrintWriter out) {
        try (final Scratch scratch = scratch(entry)) {
            final ProjectRuntime.Account account = account(entry);
            own(entry, account, scratch);
            checked(
                    entry.name(),
                    "backup command",
                    as(entry, account, entry.backup(scratch.data), "backup command"),
                    false);
            // A relative source restores at the target root, independent of this run's staging
            // path.
            checked(entry.name(), "restic backup", backup(entry, scratch.data, environment), false);
            checked(
                    entry.name(),
                    "restic forget",
                    restic(
                            entry.name(),
                            List.of(
                                    "forget",
                                    "--tag",
                                    entry.name(),
                                    "--keep-last",
                                    Integer.toString(entry.keep()),
                                    "--group-by",
                                    "tags"),
                            environment),
                    false);
            out.println(entry.name() + ": kept");
        }
    }

    private int restore(PrintWriter out) throws IOException {
        try (final var lock = lock()) {
            final Map<String, String> environment = environment();
            final List<StoreEntries.Entry> entries = StoreEntries.read(files);
            final boolean exists = repository(environment);
            for (final StoreEntries.Entry entry : entries) {
                if (!exists || !hasSnapshot(entry, environment))
                    out.println(entry.name() + ": first start");
                else restore(entry, environment, out);
            }
        }
        return 0;
    }

    private boolean hasSnapshot(StoreEntries.Entry entry, Map<String, String> environment) {
        final var result =
                restic(
                        entry.name(),
                        List.of("snapshots", "--json", "--tag", entry.name(), "--latest", "1"),
                        environment);
        checked(entry.name(), "restic snapshots", result, false);
        final @Nullable Object parsed;
        try {
            parsed = Json.parse(result.output());
        } catch (JsonException ex) {
            throw failed(entry.name(), "restic snapshots returned invalid JSON", 70);
        }
        if (!(parsed instanceof List<?> snapshots)
                || snapshots.stream()
                        .anyMatch(
                                snapshot ->
                                        !(snapshot instanceof Map<?, ?> row)
                                                || !(row.get("id") instanceof String id)
                                                || !id.matches("[0-9a-f]{64}")))
            throw failed(entry.name(), "restic snapshots returned invalid JSON", 70);
        return !snapshots.isEmpty();
    }

    private void restore(
            StoreEntries.Entry entry, Map<String, String> environment, PrintWriter out) {
        try (final Scratch scratch = scratch(entry)) {
            checked(
                    entry.name(),
                    "restic restore",
                    restic(
                            entry.name(),
                            List.of(
                                    "restore",
                                    "latest",
                                    "--tag",
                                    entry.name(),
                                    "--target",
                                    scratch.data.toString()),
                            environment),
                    false);
            final ProjectRuntime.Account account = account(entry);
            own(entry, account, scratch);
            final var result = as(entry, account, entry.restore(scratch.data), "restore command");
            checked(entry.name(), "restore command", result, true);
            out.println(entry.name() + (present(result.output()) ? ": unchanged" : ": restored"));
        }
    }

    private static boolean present(String output) {
        try {
            return Json.parse(output) instanceof Map<?, ?> answer
                    && "present".equals(answer.get("restore"));
        } catch (JsonException ex) {
            return false;
        }
    }

    private boolean repository(Map<String, String> environment) {
        final var result = restic("store", List.of("cat", "config"), environment);
        if (result.exitCode() == MISSING_REPOSITORY) return false;
        checked("store", "restic cat config", result, false);
        return true;
    }

    private List<String> resticCommand(List<String> arguments) {
        final List<String> command =
                new ArrayList<>(List.of(runtime.personProgram("restic").toString()));
        command.addAll(arguments);
        command.add("--no-cache");
        return command;
    }

    private HostPrograms.Result restic(
            String entry, List<String> arguments, Map<String, String> environment) {
        final String step = "restic " + arguments.getFirst();
        try {
            return execute(entry, step, resticCommand(arguments), environment);
        } catch (HostRefusal ex) {
            throw failed(entry, step, 70);
        }
    }

    private HostPrograms.Result backup(
            StoreEntries.Entry entry, Path data, Map<String, String> environment) {
        try {
            final List<String> command =
                    new ArrayList<>(
                            List.of(
                                    runtime.personProgram("env").toString(),
                                    "-C",
                                    data.toString()));
            command.addAll(
                    resticCommand(
                            List.of("backup", "--tag", entry.name(), "--group-by", "tags", ".")));
            return execute(entry.name(), "restic backup", command, environment);
        } catch (HostRefusal ex) {
            throw failed(entry.name(), "restic backup", 70);
        }
    }

    private HostPrograms.Result execute(
            String entry, String step, List<String> argv, Map<String, String> environment) {
        try {
            return runtime.run(argv, new byte[0], environment, Duration.ZERO);
        } catch (IOException | HostRefusal ex) {
            throw failed(entry, step, 70);
        }
    }

    private HostPrograms.Result as(
            StoreEntries.Entry entry,
            ProjectRuntime.Account account,
            List<String> command,
            String step) {
        try {
            final List<String> checked = new ArrayList<>(command);
            checked.set(0, runtime.personProgram(command.getFirst()).toString());
            return execute(
                    entry.name(),
                    step,
                    runtime.switched(account, checked),
                    HostPrograms.ENVIRONMENT);
        } catch (HostRefusal ex) {
            throw failed(entry.name(), step, 70);
        }
    }

    private static void checked(
            String entry, String step, HostPrograms.Result result, boolean restoring) {
        if (result.exitCode() != 0)
            throw failed(
                    entry,
                    step + " (exit " + result.exitCode() + ")",
                    restoring && result.exitCode() == 69 ? 69 : 70);
    }

    private static HostRefusal failed(String entry, String step, int code) {
        return new HostRefusal(
                code, "store " + entry + ": " + step + " failed; correct it and retry");
    }

    private ProjectRuntime.Account account(StoreEntries.Entry entry) {
        try {
            return runtime.required(entry.account());
        } catch (HostRefusal ex) {
            throw failed(entry.name(), "account lookup", 70);
        }
    }

    private Scratch scratch(StoreEntries.Entry entry) {
        try {
            final Path base = files.path("/var/cache/porelo-host-store");
            final Path parent =
                    java.util.Objects.requireNonNull(
                            base.getParent(), "The scratch base has a parent");
            if (!files.check(parent, 70, false, true, -1)) files.directories(parent);
            if (!files.check(base, 70, false, true, 0711)) {
                Files.createDirectory(
                        base,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx--x--x")));
                Files.setAttribute(base, "unix:mode", 0711);
            }
            final Path root =
                    Files.createTempDirectory(
                            base,
                            entry.name() + ".",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            try {
                final Path data =
                        Files.createDirectory(
                                root.resolve("data"),
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
                return new Scratch(entry.name(), root, data);
            } catch (IOException ex) {
                files.removeTree(root);
                throw ex;
            }
        } catch (IOException | HostRefusal ex) {
            throw failed(entry.name(), "staging directory", 70);
        }
    }

    private void own(StoreEntries.Entry entry, ProjectRuntime.Account account, Scratch scratch) {
        try {
            checked(
                    entry.name(),
                    "staging ownership",
                    execute(
                            entry.name(),
                            "staging ownership",
                            List.of(
                                    runtime.personProgram("chown").toString(),
                                    "--recursive",
                                    "--no-dereference",
                                    "--",
                                    Long.toString(account.uid()),
                                    scratch.data.toString()),
                            HostPrograms.ENVIRONMENT),
                    false);
            // The root-owned parent stays private until restic and chown have finished writing.
            Files.setAttribute(scratch.data, "unix:mode", 0700, LinkOption.NOFOLLOW_LINKS);
            Files.setAttribute(scratch.root, "unix:mode", 0711, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | HostRefusal ex) {
            throw failed(entry.name(), "staging ownership", 70);
        }
    }

    private final class Scratch implements AutoCloseable {
        private final String entry;
        private final Path root;
        private final Path data;

        private Scratch(String entry, Path root, Path data) {
            this.entry = entry;
            this.root = root;
            this.data = data;
        }

        /** Removes this entry's staging alone without following account-created links. */
        @Override
        public void close() {
            try {
                files.removeTree(root);
            } catch (IOException | HostRefusal ex) {
                throw failed(entry, "staging cleanup", 70);
            }
        }
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    private static final class Put implements Callable<Integer> {
        private final HostStore store;
        @Spec private CommandLine.Model.CommandSpec spec;

        private Put(HostStore store) {
            this.store = store;
        }

        /** Publishes only a complete, validated credential set. */
        @Override
        public Integer call() throws IOException {
            store.put(spec.commandLine().getOut());
            return 0;
        }
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    private static final class Keep implements Callable<Integer> {
        private final HostStore store;

        @Option(names = "--all", description = "Include entries kept at down.")
        private boolean all;

        @Spec private CommandLine.Model.CommandSpec spec;

        private Keep(HostStore store) {
            this.store = store;
        }

        /** Keeps due entries in order, stopping at the first failure. */
        @Override
        public Integer call() throws IOException {
            return store.keep(all, spec.commandLine().getOut());
        }
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    private static final class Restore implements Callable<Integer> {
        private final HostStore store;
        @Spec private CommandLine.Model.CommandSpec spec;

        private Restore(HostStore store) {
            this.store = store;
        }

        /** Restores each entry before its account's service or tasks start. */
        @Override
        public Integer call() throws IOException {
            return store.restore(spec.commandLine().getOut());
        }
    }
}
