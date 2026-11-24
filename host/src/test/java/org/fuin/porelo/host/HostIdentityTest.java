package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.host.HostJson.fields;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A host's identity leaves it only on export's standard output and comes back only onto a host that
 * holds none of it or exactly it: no key text in a refusal or on standard error, and no read key of
 * this host written over by another identity.
 */
class HostIdentityTest {
    private static final String TITLE_ALPHA = "porelo host workshop project alpha";
    private static final String TITLE_BETA = "porelo host workshop project beta";
    private static final String CODE = "https://forge.example.org/acme/code.git";
    private static final String DOCS = "https://forge.example.org/acme/docs.git";
    private static final String SITE = "ssh://git@forge.example.org/acme/Site.git";

    @TempDir static Path keyDirectory;
    private static Key organization;
    private static Key code;
    private static Key docs;
    private static Key site;
    private static Key other;
    private static Key hostEd25519;
    private static Key hostRsa;
    private static Key firstBootEd25519;
    private static Key firstBootEcdsa;

    @TempDir Path directory;
    private HostReadFixture source;
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Integer> scratchModes = new ArrayList<>();
    private ProjectFiles.Deleting deleting = path -> Files.deleteIfExists(path);

    private record Key(String privateText, String publicLine) {
        /** The base64 body of the private half, which must never leave by any other way. */
        List<String> secretLines() {
            return privateText
                    .lines()
                    .filter(line -> !line.startsWith("-----"))
                    .filter(line -> line.length() > 20)
                    .toList();
        }
    }

    @BeforeAll
    static void makeKeys() throws Exception {
        organization = keygen("organization", "ed25519", "porelo host read key SHA256:fixture");
        code = keygen("code", "ed25519", "");
        docs = keygen("docs", "ed25519", "");
        site = keygen("site", "ed25519", "");
        other = keygen("other", "ed25519", "");
        hostEd25519 = keygen("host-ed25519", "ed25519", "root@workshop");
        hostRsa = keygen("host-rsa", "rsa", "root@workshop");
        firstBootEd25519 = keygen("first-boot-ed25519", "ed25519", "root@localhost");
        firstBootEcdsa = keygen("first-boot-ecdsa", "ecdsa", "root@localhost");
    }

    private static Key keygen(String name, String type, String comment) throws Exception {
        final Path path = keyDirectory.resolve(name);
        final List<String> argv =
                new ArrayList<>(
                        List.of("/usr/bin/ssh-keygen", "-q", "-t", type, "-N", "", "-C", comment));
        if (type.equals("rsa")) argv.addAll(List.of("-b", "2048"));
        argv.addAll(List.of("-f", path.toString()));
        final Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("ssh-keygen makes the fixture key %s", name).isZero();
        return new Key(Files.readString(path), Files.readString(Path.of(path + ".pub")).strip());
    }

    @BeforeEach
    void prepareHost() throws Exception {
        source = host("source");
        put(source, "/etc/porelo-host/organization/read/organization", organization.privateText);
        put(
                source,
                "/etc/porelo-host/organization/read/organization.pub",
                organization.publicLine + "\n");
        put(source, "/etc/porelo-host/organization/read/organization.added", "added\n");
        manifest(
                source,
                "alpha",
                List.of(
                        entry("code", CODE, 7L, TITLE_ALPHA),
                        ProjectRecords.object("name", "docs", "address", DOCS, "key_id", 0L)));
        repositoryKey(source, "alpha", "code", code);
        repositoryKey(source, "alpha", "docs", docs);
        manifest(source, "beta", List.of(entry("Site", SITE, 9L, TITLE_BETA)));
        repositoryKey(source, "beta", "site", site);
        put(source, "/etc/ssh/ssh_host_ed25519_key", hostEd25519.privateText);
        put(source, "/etc/ssh/ssh_host_ed25519_key.pub", hostEd25519.publicLine + "\n", 0644);
        put(source, "/etc/ssh/ssh_host_rsa_key", hostRsa.privateText);
        put(source, "/etc/ssh/ssh_host_rsa_key.pub", hostRsa.publicLine + "\n", 0644);
    }

    /** A host as its setup leaves it before any identity: forge.conf, the lock, a scratch area. */
    private HostReadFixture host(String name) throws IOException {
        final HostReadFixture tree = new HostReadFixture(directory.resolve(name));
        put(tree, HostFollowLock.PATH, "");
        Files.setPosixFilePermissions(
                tree.path("/run/porelo-host"), PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String path : List.of("/tmp", "/etc/ssh")) {
            Files.createDirectories(tree.path(path));
            Files.setPosixFilePermissions(
                    tree.path(path), PosixFilePermissions.fromString("rwx------"));
        }
        return tree;
    }

    private static void put(HostReadFixture tree, String name, String text) throws IOException {
        put(tree, name, text, 0600);
    }

    private static void put(HostReadFixture tree, String name, String text, int mode)
            throws IOException {
        final Path path = tree.put(name, text);
        Files.setAttribute(path, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
    }

    private static Map<String, @Nullable Object> entry(
            String name, String address, long id, String title) {
        return ProjectRecords.object(
                "name", name, "address", address, "key_id", id, "title", title);
    }

    private static void manifest(
            HostReadFixture tree, String project, List<Map<String, @Nullable Object>> entries)
            throws IOException {
        put(
                tree,
                "/etc/porelo-host/organization/read/projects/" + project + "/repositories.json",
                ProjectRecords.pythonJson(entries, false) + "\n");
    }

    private static void repositoryKey(HostReadFixture tree, String project, String file, Key key)
            throws IOException {
        final String base = "/etc/porelo-host/organization/read/projects/" + project + "/" + file;
        put(tree, base, key.privateText);
        put(tree, base + ".pub", key.publicLine + "\n");
    }

    private HostReadFixture.Answer run(
            HostReadFixture tree,
            long uid,
            Map<String, String> environment,
            String input,
            String... args) {
        final HostFiles files = tree.files();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                        new ProjectFiles(tree.root, owner(tree.root), deleting),
                        this::command,
                        Path::of);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int exit =
                PoreloHost.run(
                        args,
                        environment,
                        () -> uid,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files,
                        env -> {
                            throw new AssertionError("a host's identity is root's alone");
                        },
                        new HostSweep(files),
                        runtime,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        Clock.systemUTC());
        return new HostReadFixture.Answer(exit, out.toString(), err.toString());
    }

    private HostReadFixture.Answer export(HostReadFixture tree) {
        return run(tree, 0, Map.of(), "", "identity", "export");
    }

    private HostReadFixture.Answer importing(HostReadFixture tree, String identity) {
        return run(tree, 0, Map.of(), identity, "identity", "import");
    }

    private static int owner(Path root) {
        try {
            return (int) Files.getAttribute(root, "unix:uid");
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private HostPrograms.Result command(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        commands.add(List.copyOf(argv));
        if (argv.contains("-y")) {
            scratchModes.add(
                    (int)
                            Files.getAttribute(
                                    Path.of(argv.getLast()).getParent(),
                                    "unix:mode",
                                    LinkOption.NOFOLLOW_LINKS));
        }
        assertThat(argv.getFirst())
                .as("the identity starts only ssh-keygen, to derive a public half")
                .isEqualTo("/usr/bin/ssh-keygen");
        return new HostPrograms(timeout, 1024 * 1024, Path::of).run(argv, input, environment);
    }

    private static String expectedIdentity() {
        return HostJson.write(
                        fields(
                                "host",
                                "workshop",
                                "organization",
                                fields(
                                        "public",
                                        organization.publicLine,
                                        "private",
                                        organization.privateText),
                                "repositories",
                                List.of(
                                        repository("alpha", "code", CODE, 7L, TITLE_ALPHA, code),
                                        repository("beta", "Site", SITE, 9L, TITLE_BETA, site)),
                                "ssh_host_keys",
                                List.of(
                                        fields(
                                                "type",
                                                "ed25519",
                                                "public",
                                                hostEd25519.publicLine,
                                                "private",
                                                hostEd25519.privateText),
                                        fields(
                                                "type",
                                                "rsa",
                                                "public",
                                                hostRsa.publicLine,
                                                "private",
                                                hostRsa.privateText))))
                + "\n";
    }

    private static Map<String, @Nullable Object> repository(
            String project, String name, String address, long id, String title, Key key) {
        return fields(
                "project",
                project,
                "repository",
                name,
                "address",
                address,
                "key_id",
                id,
                "title",
                title,
                "public",
                key.publicLine,
                "private",
                key.privateText);
    }

    /** Every file and directory under a root with its mode, and a file's content and time. */
    private static Map<String, String> snapshot(Path root, boolean times) throws IOException {
        final Map<String, String> entries = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return entries;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                final int mode =
                        (int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                String value = Integer.toOctalString(mode);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    value += " " + Files.readString(path);
                    if (times) {
                        value += " " + Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
                    }
                }
                entries.put(root.relativize(path).toString(), value);
            }
        }
        return entries;
    }

    private static void assertNoKeyText(HostReadFixture.Answer answer, boolean outputToo) {
        for (Key key : List.of(organization, code, docs, site, other, hostEd25519, hostRsa)) {
            for (String line : key.secretLines()) {
                assertThat(answer.err())
                        .as("standard error never holds key text")
                        .doesNotContain(line);
                if (outputToo) {
                    assertThat(answer.out())
                            .as("only export's standard output holds key text")
                            .doesNotContain(line);
                }
            }
        }
    }

    private static String edit(String identity, UnaryOperator<Map<String, Object>> change) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> parsed = (Map<String, Object>) Json.parseExact(identity);
        return Json.write(change.apply(parsed));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> identity, String list, int index) {
        return (Map<String, Object>) ((List<Object>) identity.get(list)).get(index);
    }

    @Test
    void exportAnswersTheAgreedShapeOnStandardOutputAloneAndWritesNothing() throws Exception {
        final Map<String, String> before = snapshot(source.root, true);

        final var answer = export(source);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out())
                .as(
                        "the organization key, each repository key with a key id (docs, without one,"
                                + " is left out) and the SSH host keys, in the agreed shape")
                .isEqualTo(expectedIdentity());
        assertThat(answer.err()).as("export says nothing on standard error").isEmpty();
        assertThat(snapshot(source.root, true)).as("export writes nothing").isEqualTo(before);
        assertThat(commands).as("export starts no program").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "export,SUDO_USER",
        "export,SUDO_UID",
        "export,SUDO_COMMAND",
        "import,SUDO_USER",
        "import,SUDO_GID",
        "import,SUDO_TTY"
    })
    void eitherVerbRefusesAnySudoVariableAndChangesNothing(String verb, String variable)
            throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = run(target, 0, Map.of(variable, "1000"), identity, "identity", verb);

        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("never runs through sudo");
        assertThat(answer.out()).as("a refused call answers nothing").isEmpty();
        assertThat(snapshot(target.root, true)).isEqualTo(before);
        assertNoKeyText(answer, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"export", "import"})
    void eitherVerbRefusesACallerOtherThanRoot(String verb) throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = run(target, 1000, Map.of(), identity, "identity", verb);

        assertThat(answer.code()).as(answer.err()).isEqualTo(77);
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, true)).isEqualTo(before);
    }

    @Test
    void importIntoAHostWithoutAnIdentityPutsBackTheSameFilesAndModes() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertNoKeyText(answer, true);
        final Path read = Path.of("etc/porelo-host/organization/read");
        final Map<String, String> expected = snapshot(source.root.resolve(read), false);
        expected.remove("organization.added");
        expected.remove("projects/alpha/docs");
        expected.remove("projects/alpha/docs.pub");
        expected.put(
                "projects/alpha/repositories.json",
                "100600 "
                        + ProjectRecords.pythonJson(
                                List.of(entry("code", CODE, 7L, TITLE_ALPHA)), false)
                        + "\n");
        assertThat(snapshot(target.root.resolve(read), false))
                .as("the read keys and manifests exported come back with their modes")
                .isEqualTo(expected);
        assertThat(answer.out()).isEqualTo("imported\n");
    }

    @Test
    void importOnAFreshHostReplacesItsFirstBootHostKeysWithExactlyTheIdentitys() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        put(target, "/etc/ssh/ssh_host_ed25519_key", firstBootEd25519.privateText, 0640);
        put(target, "/etc/ssh/ssh_host_ed25519_key.pub", firstBootEd25519.publicLine + "\n", 0644);
        put(target, "/etc/ssh/ssh_host_ecdsa_key", firstBootEcdsa.privateText);
        put(target, "/etc/ssh/ssh_host_ecdsa_key.pub", firstBootEcdsa.publicLine + "\n", 0644);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).isEqualTo("imported\n");
        assertNoKeyText(answer, true);
        for (Key key : List.of(firstBootEd25519, firstBootEcdsa)) {
            for (String line : key.secretLines()) {
                assertThat(answer.err()).doesNotContain(line);
            }
        }
        final Path ssh = Path.of("etc/ssh");
        assertThat(snapshot(target.root.resolve(ssh), false))
                .as(
                        "the ed25519 and rsa keys are the identity's, 0600 and 0644, and the"
                                + " first-boot ecdsa key it does not carry is gone")
                .isEqualTo(snapshot(source.root.resolve(ssh), false));
        assertThat(export(target).out())
                .as("the host now answers the identity it took")
                .isEqualTo(identity);
    }

    @Test
    void importOfTheIdentityAlreadyThereChangesNothing() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        assertThat(importing(target, identity).code()).isZero();
        final Map<String, String> once = snapshot(target.root, true);
        final Map<String, String> sourceBefore = snapshot(source.root, true);

        final var again = importing(target, identity);
        final var home = importing(source, identity);

        assertThat(again.code()).as(again.err()).isZero();
        assertThat(again.out()).isEqualTo("unchanged\n");
        assertThat(home.code()).as(home.err()).isZero();
        assertThat(home.out()).isEqualTo("unchanged\n");
        assertThat(snapshot(target.root, true)).as("a second import is a no-op").isEqualTo(once);
        assertThat(snapshot(source.root, true))
                .as("an import onto the host it came from is a no-op")
                .isEqualTo(sourceBefore);
        assertNoKeyText(again, true);
    }

    @Test
    void importAddsToAHostThatHoldsAPartOfTheSameIdentity() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        put(target, "/etc/porelo-host/organization/read/organization", organization.privateText);
        put(
                target,
                "/etc/porelo-host/organization/read/organization.pub",
                organization.publicLine + "\n");
        manifest(target, "alpha", List.of(entry("code", CODE, 7L, TITLE_ALPHA)));
        repositoryKey(target, "alpha", "code", code);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).isEqualTo("imported\n");
        assertThat(target.path("/etc/porelo-host/organization/read/projects/beta/site"))
                .hasContent(site.privateText);
        assertThat(target.path("/etc/ssh/ssh_host_ed25519_key"))
                .as("a host key missing on a host that carries the identity is written")
                .hasContent(hostEd25519.privateText);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "organization key",
                "organization public key",
                "repository key",
                "repository key id",
                "repository address",
                "key file without its entry",
                "host key",
                "host key of a type the identity does not carry"
            })
    void importWhereAFileAlreadyThereDiffersIsRefusedWithNothingChanged(String differing)
            throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = host("target");
        put(
                target,
                "/etc/porelo-host/organization/read/organization",
                differing.equals("organization key")
                        ? other.privateText
                        : organization.privateText);
        put(
                target,
                "/etc/porelo-host/organization/read/organization.pub",
                (differing.equals("organization public key")
                                ? other.publicLine
                                : organization.publicLine)
                        + "\n");
        if (differing.equals("key file without its entry")) {
            repositoryKey(target, "beta", "site", other);
            manifest(target, "beta", List.of());
        } else {
            manifest(
                    target,
                    "alpha",
                    List.of(
                            entry(
                                    "code",
                                    differing.equals("repository address")
                                            ? "https://forge.example.org/elsewhere/code.git"
                                            : CODE,
                                    differing.equals("repository key id") ? 8L : 7L,
                                    TITLE_ALPHA)));
            repositoryKey(
                    target, "alpha", "code", differing.equals("repository key") ? other : code);
        }
        if (differing.equals("host key")) {
            put(target, "/etc/ssh/ssh_host_ed25519_key", other.privateText);
            put(target, "/etc/ssh/ssh_host_ed25519_key.pub", other.publicLine + "\n", 0644);
        }
        if (differing.equals("host key of a type the identity does not carry")) {
            put(target, "/etc/ssh/ssh_host_ecdsa_key", firstBootEcdsa.privateText);
            put(target, "/etc/ssh/ssh_host_ecdsa_key.pub", firstBootEcdsa.publicLine + "\n", 0644);
        }
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("differs");
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, true))
                .as("a host's keys are never written over by another identity")
                .isEqualTo(before);
        assertNoKeyText(answer, true);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "unknown top-level field | the identity holds an unknown field",
                "unknown repository field | repositories[0] holds an unknown field",
                "unknown host key field | ssh_host_keys[0] holds an unknown field",
                "another host | the identity is another host's",
                "malformed private key | repositories[0].private is not one OpenSSH private key",
                "private key of another public key | repositories[0].private is not an unencrypted key",
                "public key of another private key | ssh_host_keys[0].private is not an unencrypted key",
                "key id zero | repositories[0].key_id is not a positive whole number",
                "title of another host | repositories[0].title is not this host's title",
                "repository not at its address | repositories[0].address does not name its repository",
                "duplicate repository | repositories[2] names a repository twice",
                "no host key | ssh_host_keys holds one to three keys",
                "host key of another type | ssh_host_keys[0].type is not its key's type",
                "not JSON | the identity is not one JSON object"
            })
    void importOfAMalformedIdentityIsRefusedWithNothingChanged(String malformed, String why)
            throws Exception {
        final String exported = export(source).out();
        final String identity =
                malformed.equals("not JSON")
                        ? exported.substring(0, exported.length() / 2)
                        : edit(
                                exported,
                                value -> {
                                    final Map<String, Object> code = at(value, "repositories", 0);
                                    final Map<String, Object> key = at(value, "ssh_host_keys", 0);
                                    switch (malformed) {
                                        case "unknown top-level field" -> value.put("extra", 1L);
                                        case "unknown repository field" -> code.put("comment", "x");
                                        case "unknown host key field" -> key.put("bits", 256L);
                                        case "another host" -> {
                                            value.put("host", "elsewhere");
                                            for (int index = 0; index < 2; index++) {
                                                final var item = at(value, "repositories", index);
                                                item.put(
                                                        "title",
                                                        "porelo host elsewhere project "
                                                                + item.get("project"));
                                            }
                                        }
                                        case "malformed private key" ->
                                                code.put(
                                                        "private",
                                                        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                                                                + "AAAA\n"
                                                                + "-----END OPENSSH PRIVATE KEY-----\n");
                                        case "private key of another public key" ->
                                                code.put("private", other.privateText);
                                        case "public key of another private key" ->
                                                key.put("public", other.publicLine);
                                        case "key id zero" -> code.put("key_id", 0L);
                                        case "title of another host" ->
                                                code.put(
                                                        "title",
                                                        "porelo host elsewhere project alpha");
                                        case "repository not at its address" ->
                                                code.put("repository", "docs");
                                        case "duplicate repository" -> {
                                            final Map<String, Object> copy =
                                                    new java.util.LinkedHashMap<>(code);
                                            copy.put("repository", "CODE");
                                            copy.put(
                                                    "address",
                                                    "https://forge.example.org/acme/CODE.git");
                                            ((List<Object>) value.get("repositories")).add(copy);
                                        }
                                        case "no host key" -> value.put("ssh_host_keys", List.of());
                                        case "host key of another type" -> key.put("type", "rsa");
                                        default -> throw new AssertionError(malformed);
                                    }
                                    return value;
                                });
        final HostReadFixture target = host("target");
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).as("the refusal names its field, never its value").contains(why);
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, true)).as("nothing changed").isEqualTo(before);
        assertNoKeyText(answer, true);
    }

    @Test
    void importRefusesAnIdentityLargerThanItsLimit() throws Exception {
        final String identity =
                edit(
                        export(source).out(),
                        value -> {
                            at(value, "repositories", 0).put("title", "x".repeat(300 * 1024));
                            return value;
                        });
        final HostReadFixture target = host("target");
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("256 KiB");
        assertThat(snapshot(target.root, true)).isEqualTo(before);
    }

    @Test
    void exportRefusesAReadKeyOthersCanReadWithoutPrintingIt() throws Exception {
        Files.setAttribute(
                source.path("/etc/porelo-host/organization/read/projects/beta/site"),
                "unix:mode",
                0640,
                LinkOption.NOFOLLOW_LINKS);

        final var answer = export(source);

        assertThat(answer.code()).as(answer.err()).isNotZero();
        assertThat(answer.out()).as("a refused export answers no part of the identity").isEmpty();
        assertNoKeyText(answer, true);
    }

    @ParameterizedTest
    @CsvSource({"export,configuration", "export,follow", "import,configuration", "import,follow"})
    void eitherVerbWaitsForAHeldConfigurationOrFollowLock(String verb, String lock)
            throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = verb.equals("import") ? host("target") : source;
        final AutoCloseable held =
                lock.equals("configuration")
                        ? HostConfigurationLock.acquire(
                                target.path("/etc/porelo-host/forge.conf"), false)
                        : HostFollowLock.acquire(target.path(HostFollowLock.PATH));
        final CompletableFuture<HostReadFixture.Answer> call;
        try {
            call =
                    CompletableFuture.supplyAsync(
                            () -> run(target, 0, Map.of(), identity, "identity", verb));
            Thread.sleep(500);
            assertThat(call).as("the %s waits while the %s lock is held", verb, lock).isNotDone();
            if (verb.equals("import")) {
                assertThat(target.path("/etc/porelo-host/organization/read/organization"))
                        .as("nothing is written while the lock is held")
                        .doesNotExist();
            }
        } finally {
            held.close();
        }
        final var answer = call.get(30, TimeUnit.SECONDS);
        assertThat(answer.code()).as(answer.err()).isZero();
    }

    /** A host as its image boots: first-boot SSH host keys, nothing carried yet. */
    private HostReadFixture firstBoot(String name) throws IOException {
        final HostReadFixture tree = host(name);
        put(tree, "/etc/ssh/ssh_host_ed25519_key", firstBootEd25519.privateText);
        put(tree, "/etc/ssh/ssh_host_ed25519_key.pub", firstBootEd25519.publicLine + "\n", 0644);
        put(tree, "/etc/ssh/ssh_host_ecdsa_key", firstBootEcdsa.privateText);
        put(tree, "/etc/ssh/ssh_host_ecdsa_key.pub", firstBootEcdsa.publicLine + "\n", 0644);
        return tree;
    }

    @Test
    void importOnAFreshHostWhoseReadDirectoryIsUnsafeIsRefusedWithItsHostKeysUnchanged()
            throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = firstBoot("target");
        final Path read = target.path("/etc/porelo-host/organization/read");
        Files.createDirectories(read);
        Files.setPosixFilePermissions(
                read.getParent(), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(read, PosixFilePermissions.fromString("rwxr-x---"));
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isNotZero();
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, true))
                .as("every precondition is checked before the first write")
                .isEqualTo(before);
        assertNoKeyText(answer, true);
    }

    @Test
    void importThatFailsAfterItsFirstWritePutsBackWhatItReplaced() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = firstBoot("target");
        final Map<String, String> before = snapshot(target.root, false);
        final boolean[] failed = {false};
        deleting =
                path -> {
                    if (!failed[0]
                            && path.toString().contains("/projects/beta/")
                            && path.getFileName().toString().startsWith(".repositories-")) {
                        failed[0] = true;
                        throw new IOException("fixture: the disk fails while beta is written");
                    }
                    Files.deleteIfExists(path);
                };

        final var answer = importing(target, identity);

        assertThat(failed[0]).as("the failure came after the host keys were written").isTrue();
        assertThat(answer.code()).as(answer.err()).isNotZero();
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, false))
                .as(
                        "the first-boot host keys are back with their modes, and nothing of the"
                                + " organization tree or a staged file is left")
                .isEqualTo(before);
        assertNoKeyText(answer, true);
    }

    @Test
    void aHostKeyAnUndoCouldNotPutBackIsKeptAndTheNextImportRefusesWhileItLies() throws Exception {
        // When putting back fails too, the kept link may be the only copy of the host's old key.
        final String identity = export(source).out();
        final HostReadFixture target = firstBoot("target");
        final Path key = target.root.resolve("etc/ssh/ssh_host_ed25519_key");
        final Path kept =
                key.resolveSibling(ProjectFiles.STAGED_PREFIX + "kept." + key.getFileName());
        final boolean[] failed = {false};
        deleting =
                path -> {
                    if (!failed[0]
                            && path.toString().contains("/projects/beta/")
                            && path.getFileName().toString().startsWith(".repositories-")) {
                        failed[0] = true;
                        // The key's place is taken, so the undo cannot move the kept key back.
                        Files.delete(key);
                        Files.createDirectories(key.resolve("taken"));
                        throw new IOException("fixture: the disk fails while beta is written");
                    }
                    Files.deleteIfExists(path);
                };

        final var failing = importing(target, identity);

        assertThat(failed[0]).as("the failure came after the host keys were written").isTrue();
        assertThat(failing.code()).as(failing.err()).isNotZero();
        assertThat(kept).as("the old key's only copy is not deleted").isRegularFile();
        assertThat(Files.readString(kept)).isEqualTo(firstBootEd25519.privateText);
        assertNoKeyText(failing, true);

        deleting = path -> Files.deleteIfExists(path);
        Files.delete(key.resolve("taken"));
        Files.delete(key);
        final Map<String, String> before = snapshot(target.root, false);

        final var again = importing(target, identity);

        assertThat(again.code()).as(again.err()).isNotZero();
        assertThat(again.err())
                .as("the refusal names the kept file, so a person can put it back or remove it")
                .contains(kept.getFileName().toString());
        assertThat(snapshot(target.root, false))
                .as("nothing changed, the kept key included")
                .isEqualTo(before);
        assertNoKeyText(again, true);
    }

    @Test
    void aHostWithAnythingUnderItsReadKeysIsNotFreshAndADifferingHostKeyIsRefused()
            throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = firstBoot("target");
        manifest(target, "alpha", List.of(entry("code", CODE, 7L, TITLE_ALPHA)));
        repositoryKey(target, "alpha", "code", code);
        final Map<String, String> before = snapshot(target.root, true);

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("differs");
        assertThat(answer.out()).isEmpty();
        assertThat(snapshot(target.root, true))
                .as("a host that holds a manifest but no organization key keeps its host keys")
                .isEqualTo(before);
    }

    @Test
    void exportWithoutAFollowLockFileCreatesNone() throws Exception {
        Files.delete(source.path(HostFollowLock.PATH));
        final Map<String, String> before = snapshot(source.root, true);

        final var answer = export(source);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).isEqualTo(expectedIdentity());
        assertThat(source.path(HostFollowLock.PATH)).doesNotExist();
        assertThat(snapshot(source.root, true)).as("export writes nothing").isEqualTo(before);
    }

    @Test
    void importDerivesPublicHalvesInARootOnlyScratchUnderRunAndRemovesALeftOne() throws Exception {
        final String identity = export(source).out();
        final HostReadFixture target = firstBoot("target");
        final Path scratch = target.path("/run/porelo-host/identity-scratch");
        put(target, "/run/porelo-host/identity-scratch/key", "left by an earlier run\n");
        Files.setPosixFilePermissions(
                target.path("/run/porelo-host"), PosixFilePermissions.fromString("rwxr-xr-x"));

        final var answer = importing(target, identity);

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(commands)
                .as("each key is derived from a file in the scratch under /run/porelo-host")
                .isNotEmpty()
                .allSatisfy(
                        argv -> assertThat(Path.of(argv.getLast()).startsWith(scratch)).isTrue());
        assertThat(scratchModes).as("the scratch is root's alone").containsOnly(040700);
        assertThat(scratch).as("the scratch is gone, the earlier run's with it").doesNotExist();
        try (var entries = Files.list(target.path("/tmp"))) {
            assertThat(entries).as("the shared /tmp is never used").isEmpty();
        }
    }

    @Test
    void exportLeavesOutAKeyRegisteredUnderAnotherTitleAndSaysWhich() throws Exception {
        manifest(
                source,
                "beta",
                List.of(entry("Site", SITE, 9L, "porelo host old-name project beta")));

        final var answer = export(source);

        assertThat(answer.code()).as(answer.err()).isZero();
        final Map<String, Object> exported = HostJson.object(Json.parseExact(answer.out()));
        final List<Object> names =
                ((List<?>) exported.get("repositories"))
                        .stream()
                                .map(item -> (Object) ((Map<?, ?>) item).get("repository"))
                                .toList();
        assertThat(names)
                .as("give-up could never remove it, and the vault refuses its title")
                .containsExactly("code");
        assertThat(answer.err())
                .isEqualTo(
                        "porelo-host: beta/Site not carried: its key is registered under another"
                                + " title\n");
        assertNoKeyText(answer, false);
    }
}
