package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

/**
 * A host's identity taken off it and put back on the next rental: its organization read key, its
 * repositories' read keys and its SSH host keys. Export answers it on standard output alone; import
 * takes it on standard input and writes only the read keys, and only onto a host that holds none of
 * them or exactly them, so no identity that is not this host's writes over its keys.
 */
final class HostIdentity {
    /** The largest identity import reads. */
    static final int MAXIMUM = 256 * 1024;

    /** The most repositories one identity carries. */
    private static final int REPOSITORIES = 256;

    /** The SSH host key types a host has, in the order export answers them. */
    private static final List<String> HOST_KEY_TYPES = List.of("ed25519", "ecdsa", "rsa");

    /** Where import derives public halves, the private key file in it, while it runs. */
    private static final String SCRATCH = "/run/porelo-host/identity-scratch";

    /** ssh-keygen deriving one public half. */
    private static final Duration KEYGEN = Duration.ofSeconds(30);

    private static final String PUBLIC =
            "(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/]+={0,2}( [ -~]{1,200})?";
    private static final String PRIVATE =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                    + "([A-Za-z0-9+/=]{1,76}\n){1,400}"
                    + "-----END OPENSSH PRIVATE KEY-----\n";
    private static final byte[] MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);

    private final HostFiles host;
    private final ProjectRuntime runtime;
    private final ProjectFiles files;
    private final ProjectReadKeys keys;
    private final InputStream input;
    private final Map<String, String> environment;
    private final Clock clock;

    private HostIdentity(
            HostFiles host,
            ProjectRuntime runtime,
            InputStream input,
            Map<String, String> environment,
            Clock clock) {
        this.host = host;
        this.runtime = runtime;
        this.files = runtime.files();
        this.keys = new ProjectReadKeys(files, runtime);
        this.input = input;
        this.environment = environment;
        this.clock = clock;
    }

    static CommandLine commandLine(
            HostFiles host,
            ProjectRuntime runtime,
            InputStream input,
            Map<String, String> environment,
            Clock clock) {
        final HostIdentity identity = new HostIdentity(host, runtime, input, environment, clock);
        return new CommandLine(new PoreloHost.Group())
                .addSubcommand("export", new Export(identity))
                .addSubcommand("import", new Import(identity));
    }

    record Key(String publicKey, String privateKey) {
        Map<String, @Nullable Object> json() {
            return HostJson.fields("public", publicKey, "private", privateKey);
        }

        @Override
        public String toString() {
            return "Key[" + publicKey + "]";
        }
    }

    record Repository(
            String project, String name, String address, long keyId, String title, Key key) {
        Map<String, @Nullable Object> json() {
            return HostJson.fields(
                    "project",
                    project,
                    "repository",
                    name,
                    "address",
                    address,
                    "key_id",
                    keyId,
                    "title",
                    title,
                    "public",
                    key.publicKey(),
                    "private",
                    key.privateKey());
        }

        /** The manifest's entry for this repository, as the migration writes it. */
        Map<String, @Nullable Object> entry() {
            return ProjectRecords.object(
                    "name", name, "address", address, "key_id", keyId, "title", title);
        }
    }

    record HostKey(String type, Key key) {
        Map<String, @Nullable Object> json() {
            return HostJson.fields(
                    "type", type, "public", key.publicKey(), "private", key.privateKey());
        }
    }

    record Identity(
            String host, Key organization, List<Repository> repositories, List<HostKey> hostKeys) {
        Map<String, @Nullable Object> json() {
            return HostJson.fields(
                    "host",
                    host,
                    "organization",
                    organization.json(),
                    "repositories",
                    repositories.stream().map(Repository::json).toList(),
                    "ssh_host_keys",
                    hostKeys.stream().map(HostKey::json).toList());
        }
    }

    @FunctionalInterface
    private interface Locked<T> {
        T run() throws IOException;
    }

    private static void refuseSudo(String verb, Map<String, String> environment) {
        if (environment.keySet().stream().anyMatch(name -> name.startsWith("SUDO_"))) {
            throw new HostRefusal(
                    65, "identity " + verb + " is root's alone and never runs through sudo");
        }
    }

    /**
     * Holds the configuration lock and then the follow lock, as the follow takes them.
     *
     * @param writes Whether the work writes; work that writes nothing makes no lock file.
     */
    private <T> T locked(PrintWriter err, boolean writes, Locked<T> work) throws IOException {
        final Path conf = host.path("/etc/porelo-host/forge.conf");
        host.check(conf, 69, true, false, -1);
        try (var lock = HostConfigurationLock.acquire(conf, false)) {
            final Path followLock = host.path(HostFollowLock.PATH);
            if (!host.check(followLock, 69, false, false, 0600)) {
                if (!writes) {
                    // No follow has made its lock file since boot, since /run is cleared then, and
                    // a
                    // follow takes the configuration lock held here before it makes or takes its
                    // own: no follow runs now or starts before this work ends.
                    return work.run();
                }
                new FollowContext(host, files, runtime, clock, err)
                        .write(HostFollowLock.PATH, "", 0600);
            }
            host.check(followLock, 69, true, false, 0600);
            try (var following = HostFollowLock.acquire(followLock)) {
                return work.run();
            }
        }
    }

    private String hostName() throws IOException {
        final ProjectRecords records = new ProjectRecords(files);
        return ProjectRecords.setting(
                records.values(records.conf(), 69),
                "host",
                "[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?",
                records.conf(),
                69);
    }

    private static String title(String host, String project) {
        return "porelo host " + host + " project " + project;
    }

    private Path read() {
        return files.etc.resolve("organization/read");
    }

    // Export.

    private Identity export(PrintWriter err) throws IOException {
        final String name = hostName();
        final Path read = read();
        files.directory(read, true, false);
        final Key organization =
                new Key(
                        publicFile(read.resolve("organization.pub"), true, "the organization key"),
                        privateFile(read.resolve("organization"), true, "the organization key"));
        final List<Repository> repositories = new ArrayList<>();
        final Path projects = read.resolve("projects");
        if (files.directory(projects, true, true)) {
            for (Path path : files.list(projects)) {
                final String project = path.getFileName().toString();
                if (!project.matches(HostRecords.PROJECT)) {
                    throw new HostRefusal(
                            65, "the host's read keys hold an entry that names no project");
                }
                final ProjectReadKeys.Manifest manifest = keys.manifest(project);
                for (Map<String, @Nullable Object> item : manifest.entries()) {
                    if (ProjectReadKeys.id(item) <= 0) continue;
                    final String repository = ProjectReadKeys.name(item);
                    if (item.get("title") instanceof String recorded
                            && !recorded.equals(title(name, project))) {
                        // A key the migration reused under its earlier title: give-up could never
                        // remove it by this host's title, and the vault refuses that title.
                        err.println(
                                "porelo-host: "
                                        + project
                                        + "/"
                                        + repository
                                        + " not carried: its key is registered under another title");
                        continue;
                    }
                    final Path key =
                            manifest.directory().resolve(ProjectReadKeys.lower(repository));
                    final String what = "the read key of " + project + "/" + repository;
                    repositories.add(
                            new Repository(
                                    project,
                                    repository,
                                    ProjectReadKeys.address(item),
                                    ProjectReadKeys.id(item),
                                    title(name, project),
                                    new Key(
                                            publicFile(Path.of(key + ".pub"), true, what),
                                            privateFile(key, true, what))));
                }
            }
        }
        final List<HostKey> hostKeys = new ArrayList<>();
        for (String type : HOST_KEY_TYPES) {
            final Path key = files.at("/etc/ssh/ssh_host_" + type + "_key");
            if (!files.exists(key)) continue;
            final String what = "the " + type + " SSH host key";
            hostKeys.add(
                    new HostKey(
                            type,
                            new Key(
                                    publicFile(Path.of(key + ".pub"), false, what),
                                    privateFile(key, false, what))));
        }
        if (hostKeys.isEmpty()) {
            throw new HostRefusal(69, "the host has no SSH host key to export");
        }
        return new Identity(name, organization, repositories, hostKeys);
    }

    /** One public key line, ended by one newline, from a file of root's. */
    private String publicFile(Path path, boolean readKey, String what) throws IOException {
        trusted(path, readKey, false);
        final String text = files.text(path, readKey);
        final String line = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        if (!line.matches(PUBLIC)) {
            throw new HostRefusal(65, what + "'s public half is not one OpenSSH public key line");
        }
        return line;
    }

    private String privateFile(Path path, boolean readKey, String what) throws IOException {
        trusted(path, readKey, true);
        final String text = files.text(path, readKey);
        structure(text, what);
        return text;
    }

    /**
     * A read key is root's alone to read. An SSH host key is the system's: a private half that a
     * group may read, as some systems give one, is taken; one that others may read is not.
     */
    private void trusted(Path path, boolean readKey, boolean secret) throws IOException {
        files.trusted(path, readKey);
        if (!readKey && secret) {
            if ((mode(path) & 0007) != 0) {
                throw new HostRefusal(69, path + " is readable by others");
            }
        }
    }

    // Import.

    /**
     * Puts an identity back, checking everything before writing anything.
     *
     * @return Whether any file was written or removed.
     */
    private boolean importIdentity(Identity identity) throws IOException {
        final String name = hostName();
        if (!identity.host().equals(name)) {
            throw new HostRefusal(65, "the identity is another host's");
        }
        final Path read = read();
        final Path projects = read.resolve("projects");
        final Path organization = read.resolve("organization");
        // A host with nothing at all under its read keys has never carried an identity: its host
        // keys are the first-boot pair the image made. Anything there makes it a carrying host.
        checkDirectory(files.etc.resolve("organization"), false);
        checkDirectory(read, true);
        final boolean fresh = !files.exists(read) || files.list(read).isEmpty();
        final List<HostWrite> hostWrites = hostKeys(identity, fresh);
        final Map<Path, String> writes = new LinkedHashMap<>();
        final Map<String, ProjectReadKeys.Manifest> manifests = new LinkedHashMap<>();
        final Set<String> grown = new HashSet<>();
        plan(organization, identity.organization().privateKey(), writes);
        plan(Path.of(organization + ".pub"), identity.organization().publicKey() + "\n", writes);
        for (Repository repository : identity.repositories()) {
            ProjectReadKeys.Manifest manifest = manifests.get(repository.project());
            if (manifest == null) {
                // On a host without read keys yet, organization/ itself may still be missing.
                manifest =
                        files.exists(projects)
                                ? keys.manifest(repository.project())
                                : new ProjectReadKeys.Manifest(
                                        projects.resolve(repository.project()), new ArrayList<>());
                manifests.put(repository.project(), manifest);
            }
            final Path key = manifest.directory().resolve(ProjectReadKeys.lower(repository.name()));
            plan(key, repository.key().privateKey(), writes);
            plan(Path.of(key + ".pub"), repository.key().publicKey() + "\n", writes);
            final Map<String, @Nullable Object> entry = manifest.find(repository.name(), true);
            if (entry == null) {
                manifest.entries().add(repository.entry());
                grown.add(repository.project());
            } else if (!entry.equals(repository.entry())) {
                throw differs();
            }
        }
        checkDirectory(projects, true);
        for (ProjectReadKeys.Manifest manifest : manifests.values()) {
            checkDirectory(manifest.directory(), true);
        }
        // Every precondition is checked; from here a failure puts back what was replaced.
        final List<ProjectFiles.Writing> undo = new ArrayList<>();
        final List<Path> backups = new ArrayList<>();
        try {
            // The host keys first, then the organization tree.
            for (HostWrite write : hostWrites) {
                keep(write.path(), undo, backups);
                write.apply(files);
            }
            if (!writes.isEmpty() || !grown.isEmpty()) {
                directory(files.etc.resolve("organization"), false, undo);
                directory(read, true, undo);
                directory(projects, true, undo);
                for (ProjectReadKeys.Manifest manifest : manifests.values()) {
                    directory(manifest.directory(), true, undo);
                }
                for (Map.Entry<Path, String> write : writes.entrySet()) {
                    final Path path = write.getKey();
                    undo.add(() -> files.delete(path));
                    files.publish(path, ".repositories-", write.getValue());
                }
                // The keys are in place before the manifest names them.
                for (String project : grown) {
                    final ProjectReadKeys.Manifest manifest =
                            java.util.Objects.requireNonNull(
                                    manifests.get(project), "A grown manifest was read");
                    keep(manifest.directory().resolve("repositories.json"), undo, backups);
                    keys.writeManifest(manifest);
                }
            }
        } catch (IOException | RuntimeException ex) {
            for (ProjectFiles.Writing step : undo.reversed()) {
                try {
                    step.run();
                } catch (IOException | RuntimeException failed) {
                    ex.addSuppressed(failed);
                }
            }
            throw ex;
        } finally {
            for (Path backup : backups) {
                files.delete(backup);
            }
        }
        return !hostWrites.isEmpty() || !writes.isEmpty() || !grown.isEmpty();
    }

    /**
     * Keeps a file about to be replaced or removed as a second link beside it, so a later failure
     * puts the same file back, its owner, group and mode with it; a file that was missing is
     * removed again.
     */
    private void keep(Path path, List<ProjectFiles.Writing> undo, List<Path> backups)
            throws IOException {
        if (!files.exists(path)) {
            undo.add(() -> files.delete(path));
            return;
        }
        final Path backup =
                ProjectFiles.parent(path)
                        .resolve(ProjectFiles.STAGED_PREFIX + "kept." + path.getFileName());
        // One an earlier run left is its own kept copy, which that run no longer needs.
        files.delete(backup);
        ProjectFiles.write(path, () -> Files.createLink(backup, path));
        backups.add(backup);
        undo.add(() -> files.replace(backup, path));
    }

    /** One SSH host key file to write whole by an atomic move, or, without content, to remove. */
    private record HostWrite(Path path, @Nullable String content, int mode) {
        void apply(ProjectFiles files) throws IOException {
            final String text = content;
            if (text == null) {
                files.delete(path);
                return;
            }
            final Path staged = files.stage(path, ProjectFiles.STAGED_PREFIX, text);
            try {
                ProjectFiles.write(
                        path,
                        () ->
                                Files.setAttribute(
                                        staged,
                                        "unix:mode",
                                        mode,
                                        java.nio.file.LinkOption.NOFOLLOW_LINKS));
                files.replace(staged, path);
            } finally {
                files.delete(staged);
            }
        }
    }

    /**
     * Plans the SSH host keys: on a fresh host they become exactly the identity's, a type it does
     * not carry removed; on a host that carries an identity, one missing is written and one that
     * differs, or one of a type the identity does not carry, refuses the import.
     */
    private List<HostWrite> hostKeys(Identity identity, boolean fresh) throws IOException {
        final Path ssh = files.at("/etc/ssh");
        files.directory(ssh, false, false);
        final List<HostWrite> writes = new ArrayList<>();
        for (String type : HOST_KEY_TYPES) {
            final Path key = ssh.resolve("ssh_host_" + type + "_key");
            final @Nullable HostKey carried =
                    identity.hostKeys().stream()
                            .filter(item -> item.type().equals(type))
                            .findFirst()
                            .orElse(null);
            for (Path path : List.of(key, Path.of(key + ".pub"))) {
                final boolean secret = path.equals(key);
                final int mode = secret ? 0600 : 0644;
                final @Nullable String content =
                        carried == null
                                ? null
                                : secret
                                        ? carried.key().privateKey()
                                        : carried.key().publicKey() + "\n";
                if (!files.exists(path)) {
                    if (content != null) writes.add(new HostWrite(path, content, mode));
                    continue;
                }
                files.trusted(path, false);
                if (content == null) {
                    if (!fresh) throw differs();
                    writes.add(new HostWrite(path, null, 0));
                } else if (!files.text(path, false).equals(content)) {
                    if (!fresh) throw differs();
                    writes.add(new HostWrite(path, content, mode));
                } else if (fresh && (mode(path) & 07777) != mode) {
                    writes.add(new HostWrite(path, content, mode));
                }
            }
        }
        return writes;
    }

    private int mode(Path path) throws IOException {
        return (int)
                java.util.Objects.requireNonNull(
                        files.attributes(path).get("mode"), "Unix attributes include mode");
    }

    /**
     * Notes a file to write when it is missing, and refuses when one there differs.
     *
     * @return Whether the file is to be written.
     */
    private boolean plan(Path path, String content, Map<Path, String> writes) throws IOException {
        if (!files.exists(path)) {
            writes.put(path, content);
            return true;
        }
        files.trusted(path, true);
        final String present = files.text(path, true);
        if (!present.equals(content)) {
            throw differs();
        }
        return false;
    }

    /** Checks a directory that is there; one that is missing is made later. */
    private void checkDirectory(Path path, boolean privateDirectory) throws IOException {
        if (files.exists(path)) {
            files.directory(path, privateDirectory, false);
        }
    }

    private void directory(Path path, boolean privateDirectory, List<ProjectFiles.Writing> undo)
            throws IOException {
        if (files.exists(path)) {
            files.directory(path, privateDirectory, false);
        } else {
            undo.add(() -> files.delete(path));
            files.mkdir(path, 0700);
        }
    }

    private static HostRefusal differs() {
        return new HostRefusal(
                65,
                "a key or manifest entry on this host differs from the identity; nothing"
                        + " changed");
    }

    // Reading and checking an identity.

    private byte[] supplied() throws IOException {
        final byte[] bytes = input.readNBytes(MAXIMUM + 1);
        if (bytes.length > MAXIMUM) {
            Arrays.fill(bytes, (byte) 0);
            throw new HostRefusal(65, "the identity is larger than 256 KiB");
        }
        return bytes;
    }

    Identity parse(byte[] bytes) throws IOException {
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException ex) {
            throw malformed("the identity is not UTF-8");
        }
        final Object parsed = ProjectRecords.json(text, "the identity is not one JSON object", 65);
        final Map<?, ?> value =
                object(parsed, Set.of("host", "organization", "repositories", "ssh_host_keys"), "");
        final String name = string(value, "host", "[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?", "");
        final Key organization =
                key(
                        object(
                                value.get("organization"),
                                Set.of("public", "private"),
                                "organization"),
                        "organization");
        final List<?> items = list(value.get("repositories"), "repositories");
        if (items.size() > REPOSITORIES) {
            throw malformed("the identity carries more than 256 repositories");
        }
        final List<Repository> repositories = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            final String where = "repositories[" + index + "]";
            final Map<?, ?> item =
                    object(
                            items.get(index),
                            Set.of(
                                    "project",
                                    "repository",
                                    "address",
                                    "key_id",
                                    "title",
                                    "public",
                                    "private"),
                            where);
            final String project = string(item, "project", HostRecords.PROJECT, where);
            final String repository = string(item, "repository", ProjectRecords.REPOSITORY, where);
            final String address = string(item, "address", "[!-~]{1,2048}", where);
            if (!ProjectAddress.parse(address).repository().equals(repository)) {
                throw malformed(where + ".address does not name its repository");
            }
            if (!(item.get("key_id") instanceof Long id) || id <= 0) {
                throw malformed(where + ".key_id is not a positive whole number");
            }
            if (!title(name, project).equals(item.get("title"))) {
                throw malformed(where + ".title is not this host's title for its project");
            }
            if (!seen.add(project + "/" + ProjectReadKeys.lower(repository))) {
                throw malformed(where + " names a repository twice");
            }
            repositories.add(
                    new Repository(
                            project,
                            repository,
                            address,
                            id,
                            title(name, project),
                            key(item, where)));
        }
        final List<?> hostItems = list(value.get("ssh_host_keys"), "ssh_host_keys");
        if (hostItems.isEmpty() || hostItems.size() > HOST_KEY_TYPES.size()) {
            throw malformed("ssh_host_keys holds one to three keys");
        }
        final List<HostKey> hostKeys = new ArrayList<>();
        final Set<String> types = new HashSet<>();
        for (int index = 0; index < hostItems.size(); index++) {
            final String where = "ssh_host_keys[" + index + "]";
            final Map<?, ?> item =
                    object(hostItems.get(index), Set.of("type", "public", "private"), where);
            final String type = string(item, "type", "ed25519|ecdsa|rsa", where);
            if (!types.add(type)) {
                throw malformed(where + ".type names a type twice");
            }
            final Key key = key(item, where);
            final String algorithm = key.publicKey().split(" ", 2)[0];
            if (!(type.equals("ed25519")
                    ? algorithm.equals("ssh-ed25519")
                    : type.equals("rsa")
                            ? algorithm.equals("ssh-rsa")
                            : algorithm.startsWith("ecdsa-"))) {
                throw malformed(where + ".type is not its key's type");
            }
            hostKeys.add(new HostKey(type, key));
        }
        return new Identity(name, organization, repositories, hostKeys);
    }

    private static Map<?, ?> object(@Nullable Object value, Set<String> names, String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw malformed((where.isEmpty() ? "the identity" : where) + " is not an object");
        }
        if (!map.keySet().equals(names)) {
            throw malformed(
                    (where.isEmpty() ? "the identity" : where)
                            + " holds an unknown field or misses one");
        }
        return map;
    }

    private static List<?> list(@Nullable Object value, String where) {
        if (!(value instanceof List<?> list)) {
            throw malformed(where + " is not a list");
        }
        return list;
    }

    private static String string(Map<?, ?> map, String field, String pattern, String where) {
        if (!(map.get(field) instanceof String text) || !text.matches(pattern)) {
            throw malformed((where.isEmpty() ? "" : where + ".") + field + " is malformed");
        }
        return text;
    }

    private static Key key(Map<?, ?> map, String where) {
        if (!(map.get("public") instanceof String publicKey) || !publicKey.matches(PUBLIC)) {
            throw malformed(where + ".public is not one OpenSSH public key line");
        }
        if (!(map.get("private") instanceof String privateKey)) {
            throw malformed(where + ".private is not a string");
        }
        structure(privateKey, where + ".private");
        return new Key(publicKey, privateKey);
    }

    /**
     * Checks a private key's form before any program reads it: one unencrypted OpenSSH key.
     *
     * @param text The key file's text.
     * @param what What the key is, for a refusal that never holds key text.
     */
    static void structure(String text, String what) {
        if (!text.matches(PRIVATE)) {
            throw malformed(what + " is not one OpenSSH private key");
        }
        final byte[] bytes;
        try {
            bytes =
                    Base64.getDecoder()
                            .decode(
                                    text.lines()
                                            .filter(line -> !line.startsWith("-----"))
                                            .reduce("", String::concat));
        } catch (IllegalArgumentException ex) {
            throw malformed(what + " is not one OpenSSH private key");
        }
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            final byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, MAGIC)
                    || !"none".equals(field(buffer))
                    || !"none".equals(field(buffer))
                    || !field(buffer).isEmpty()
                    || buffer.getInt() != 1) {
                throw malformed(what + " is not one unencrypted OpenSSH private key");
            }
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException ex) {
            throw malformed(what + " is not one OpenSSH private key");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String field(ByteBuffer buffer) {
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining() || length > 64) {
            throw new IllegalArgumentException("Field length out of range");
        }
        final byte[] value = new byte[length];
        buffer.get(value);
        return new String(value, StandardCharsets.US_ASCII);
    }

    /**
     * Derives each key's public half from its private one with ssh-keygen, through a private file
     * in a private scratch directory, and refuses a key whose public half differs.
     */
    private void derive(Identity identity, PrintWriter err) throws IOException {
        final Map<String, Key> named = new LinkedHashMap<>();
        named.put("organization", identity.organization());
        for (int index = 0; index < identity.repositories().size(); index++) {
            named.put("repositories[" + index + "]", identity.repositories().get(index).key());
        }
        for (int index = 0; index < identity.hostKeys().size(); index++) {
            named.put("ssh_host_keys[" + index + "]", identity.hostKeys().get(index).key());
        }
        // Under /run, which a reboot clears, in a directory root's alone; never the shared /tmp.
        final Path scratch = files.at(SCRATCH);
        new FollowContext(host, files, runtime, clock, err)
                .directories(ProjectFiles.parent(scratch), 0700);
        if (files.exists(scratch)) {
            // An earlier run that ended before its own cleanup.
            files.removeTree(scratch);
        }
        files.mkdir(scratch, 0700);
        try {
            final Path file = scratch.resolve("key");
            for (Map.Entry<String, Key> entry : named.entrySet()) {
                Files.createFile(
                        file,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                try {
                    Files.writeString(
                            file,
                            entry.getValue().privateKey(),
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    final HostPrograms.Result derived =
                            runtime.run(
                                    List.of(
                                            runtime.checked("/usr/bin/ssh-keygen").toString(),
                                            "-y",
                                            "-P",
                                            "",
                                            "-f",
                                            file.toString()),
                                    new byte[0],
                                    ProjectRuntime.SAFE_ENV,
                                    runtime.limits().machines(KEYGEN));
                    if (derived.exitCode() != 0
                            || !canonical(derived.output().strip())
                                    .equals(canonical(entry.getValue().publicKey()))) {
                        throw malformed(
                                entry.getKey()
                                        + ".private is not an unencrypted key whose public half"
                                        + " is its public");
                    }
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        } finally {
            files.removeTree(scratch);
        }
    }

    /** A public key's type and base64, without its comment. */
    private static String canonical(String line) {
        final String[] parts = line.split(" ", 3);
        return parts.length < 2 ? "" : parts[0] + " " + parts[1];
    }

    private static HostRefusal malformed(String message) {
        return new HostRefusal(65, "the identity is malformed: " + message);
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Print this host's identity, its read keys and SSH host keys, as JSON.")
    private static final class Export implements Callable<Integer> {
        private final HostIdentity identity;
        @Spec private CommandLine.Model.CommandSpec spec;

        private Export(HostIdentity identity) {
            this.identity = identity;
        }

        @Override
        public Integer call() throws IOException {
            refuseSudo("export", identity.environment);
            final PrintWriter err = spec.commandLine().getErr();
            final Identity exported = identity.locked(err, false, () -> identity.export(err));
            final PrintWriter out = spec.commandLine().getOut();
            out.println(HostJson.write(exported.json()));
            out.flush();
            return 0;
        }
    }

    @Command(
            name = "import",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Put back this host's read keys from an exported identity on stdin.")
    private static final class Import implements Callable<Integer> {
        private final HostIdentity identity;
        @Spec private CommandLine.Model.CommandSpec spec;

        private Import(HostIdentity identity) {
            this.identity = identity;
        }

        @Override
        public Integer call() throws IOException {
            refuseSudo("import", identity.environment);
            final byte[] supplied = identity.supplied();
            try {
                final Identity parsed = identity.parse(supplied);
                final PrintWriter err = spec.commandLine().getErr();
                final boolean changed =
                        identity.locked(
                                err,
                                true,
                                () -> {
                                    identity.derive(parsed, err);
                                    return identity.importIdentity(parsed);
                                });
                spec.commandLine().getOut().println(changed ? "imported" : "unchanged");
                return 0;
            } finally {
                Arrays.fill(supplied, (byte) 0);
            }
        }
    }
}
