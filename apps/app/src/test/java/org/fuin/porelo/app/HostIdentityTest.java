package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HostIdentity}: the value a host's identity is kept as in the person's vault,
 * checked field by field, each refusal naming its field and never a key.
 *
 * <p>Ed25519 keys are made here; ECDSA and RSA keys by {@code ssh-keygen} in the test's own
 * directory, whose public halves are derived by the same {@code ssh-keygen} the daemon runs.
 */
class HostIdentityTest {

    private static final String HOST = "build-1";

    private static final String ORIGIN = "https://forge.example";

    @TempDir Path dir;

    /** Derives as the daemon does, into a directory of the test's own. */
    private HostIdentity.PublicHalf derive() {
        return HostIdentity.derivation(new ProcessCommandRunner(), dir.resolve("derivations"));
    }

    /** A key pair: its private file's text and its public line, with a comment. */
    private record Pair(String privateKey, String publicKey) {}

    private static Pair ed25519(final String comment) {
        final SigningKey key = SigningKey.generate(comment);
        final byte[] text = key.openSshPrivateKey();
        try {
            return new Pair(new String(text, StandardCharsets.US_ASCII), key.authorizedKeysLine());
        } finally {
            java.util.Arrays.fill(text, (byte) 0);
        }
    }

    private Pair keygen(final String name, final String... how)
            throws IOException, InterruptedException {
        final Path file = dir.resolve(name);
        final List<String> command =
                new ArrayList<>(
                        List.of("ssh-keygen", "-q", "-f", file.toString(), "-C", "host@fixture"));
        command.addAll(List.of(how));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor()).as("ssh-keygen").isZero();
        return new Pair(
                Files.readString(file), Files.readString(dir.resolve(name + ".pub")).strip());
    }

    private static Map<String, Object> key(final Pair pair) {
        final Map<String, Object> key = new LinkedHashMap<>();
        key.put("public", pair.publicKey());
        key.put("private", pair.privateKey());
        return key;
    }

    private static Map<String, Object> repository(
            final String project, final String repository, final long id, final Pair pair) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("project", project);
        row.put("repository", repository);
        row.put("address", ORIGIN + "/" + project + "/" + repository + ".git");
        row.put("key_id", id);
        row.put("title", "porelo host " + HOST + " project " + project);
        row.put("public", pair.publicKey());
        row.put("private", pair.privateKey());
        return row;
    }

    private static Map<String, Object> hostKey(final String type, final Pair pair) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("public", pair.publicKey());
        row.put("private", pair.privateKey());
        return row;
    }

    /** A whole identity: the organization's key, one repository's, one Ed25519 host key. */
    private static Map<String, Object> identity() {
        final Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("host", HOST);
        identity.put("organization", key(ed25519("porelo host read key")));
        identity.put(
                "repositories",
                new ArrayList<>(List.of(repository("web", "site", 7, ed25519("site")))));
        identity.put(
                "ssh_host_keys", new ArrayList<>(List.of(hostKey("ed25519", ed25519("root@h")))));
        return identity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(final Map<String, Object> identity, final String path) {
        Object here = identity;
        for (final String step : path.split("\\.")) {
            here =
                    step.matches("[0-9]+")
                            ? ((List<Object>) here).get(Integer.parseInt(step))
                            : ((Map<String, Object>) here).get(step);
        }
        return (Map<String, Object>) here;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Map<String, Object> identity, final String field) {
        return (List<Object>) identity.get(field);
    }

    private Map<String, Object> checked(final Object identity, final @Nullable String origin) {
        return HostIdentity.checked(identity, HOST, origin, derive());
    }

    /** Checks a value that must be refused, and that the refusal names the field alone. */
    private void refused(
            final Map<String, Object> identity,
            final @Nullable String origin,
            final String field,
            final String why) {
        final HostIdentity.Refused refusal =
                catchThrowableOfType(HostIdentity.Refused.class, () -> checked(identity, origin));
        assertThat(refusal).as(why + ": refused").isNotNull();
        assertThat(refusal.field()).as(why + ": the field named").isEqualTo(field);
        assertThat(refusal.getMessage())
                .as(why + ": the refusal says the field and nothing of a key")
                .isEqualTo(field)
                .doesNotContain("PRIVATE KEY")
                .doesNotContain("AAAA");
        assertThat(refusal.getCause()).as(why + ": no cause carries a key's text").isNull();
    }

    @Test
    void aWholeIdentityIsTakenAsItIsGiven() {
        final Map<String, Object> given = identity();

        assertThat(checked(given, ORIGIN)).as("the value kept").isEqualTo(given);
    }

    @Test
    void anIdentityWithEcdsaAndRsaHostKeysIsTakenWithTheirPublicHalvesDerived() throws Exception {
        final Map<String, Object> given = identity();
        list(given, "ssh_host_keys")
                .add(hostKey("ecdsa", keygen("ecdsa", "-t", "ecdsa", "-N", "")));
        list(given, "ssh_host_keys")
                .add(hostKey("rsa", keygen("rsa", "-t", "rsa", "-b", "3072", "-N", "")));

        assertThat(checked(given, ORIGIN)).as("the value kept").isEqualTo(given);
    }

    @Test
    void anUnknownFieldIsRefusedWhereverItIs() {
        final Map<String, Object> top = identity();
        top.put("comment", "x");
        refused(top, ORIGIN, "identity: an unknown field", "a field beside the four");

        final Map<String, Object> organization = identity();
        at(organization, "organization").put("title", "x");
        refused(
                organization,
                ORIGIN,
                "identity.organization: an unknown field",
                "in the organization's key");

        final Map<String, Object> repository = identity();
        at(repository, "repositories.0").put("token", "x");
        refused(
                repository,
                ORIGIN,
                "identity.repositories[0]: an unknown field",
                "in a repository");

        final Map<String, Object> hostKey = identity();
        at(hostKey, "ssh_host_keys.0").put("comment", "x");
        refused(hostKey, ORIGIN, "identity.ssh_host_keys[0]: an unknown field", "in a host key");

        final Map<String, Object> chosen = identity();
        chosen.put("\u001b]0;owned\u0007", "x");
        refused(chosen, ORIGIN, "identity: an unknown field", "a name the caller chose");
    }

    @Test
    void anEd25519KeyWhosePublicSectionsAreNotItsSeedsIsRefused() {
        final SigningKey seeded = SigningKey.generate("seeded");
        final SigningKey other = SigningKey.generate("other");
        final byte[] seed = java.util.Base64.getDecoder().decode(seeded.seedBase64());
        final byte[] blob = other.keyBlob();
        final byte[] otherPublic =
                java.util.Arrays.copyOfRange(blob, blob.length - 32, blob.length);
        final String mixed =
                new String(
                        org.fuin.porelo.vault.OpenSshPrivateKey.write(seed, otherPublic, "mixed"),
                        StandardCharsets.US_ASCII);
        final Map<String, Object> identity = identity();
        at(identity, "organization").put("private", mixed);
        at(identity, "organization").put("public", seeded.authorizedKeysLine());

        refused(
                identity,
                ORIGIN,
                "identity.organization.public",
                "a file whose public sections name another key than its seed");
    }

    @Test
    void aKeptValueIsCheckedAgainWithoutDerivingAnything() throws Exception {
        final Map<String, Object> kept = checked(identity(), ORIGIN);
        final Pair one = keygen("one", "-t", "ecdsa", "-N", "");
        final Pair other = keygen("other", "-t", "ecdsa", "-N", "");
        list(kept, "ssh_host_keys")
                .add(hostKey("ecdsa", new Pair(one.privateKey(), other.publicKey())));

        assertThat(HostIdentity.checkedAsKept(kept, HOST, ORIGIN))
                .as("its fields hold, and no public half is derived to see that it differs")
                .isEqualTo(kept);

        final Map<String, Object> unknown = copy(kept);
        at(unknown, "organization").put("title", "x");
        final HostIdentity.Refused refusal =
                catchThrowableOfType(
                        HostIdentity.Refused.class,
                        () -> HostIdentity.checkedAsKept(unknown, HOST, ORIGIN));
        assertThat(refusal.field()).isEqualTo("identity.organization: an unknown field");
    }

    @Test
    void aPublicHalfIsDerivedFromACopyInItsOwnDirectoryThatIsGoneAfterwards() throws Exception {
        final Pair ecdsa = keygen("ecdsa", "-t", "ecdsa", "-N", "");
        final Path keys = dir.resolve("run/porelo/host-identity-keys");
        final java.util.List<java.util.Set<java.nio.file.attribute.PosixFilePermission>> modes =
                new ArrayList<>();
        final java.util.List<Path> copies = new ArrayList<>();
        final org.fuin.porelo.core.process.CommandRunner runner =
                command -> {
                    final Path copy = Path.of(command.arguments().getLast());
                    copies.add(copy);
                    try {
                        modes.add(Files.getPosixFilePermissions(copy.getParent()));
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                    return new org.fuin.porelo.core.process.CommandResult(
                            command, 0, ecdsa.publicKey() + "\n", "");
                };

        assertThat(
                        HostIdentity.derivation(runner, keys)
                                .of(ecdsa.privateKey(), "ecdsa-sha2-nistp256"))
                .isEqualTo(String.join(" ", List.of(ecdsa.publicKey().split(" ")).subList(0, 2)));

        // Compared as written: the copy is gone, so nothing can resolve it.
        assertThat(copies)
                .singleElement()
                .satisfies(copy -> assertThat(copy.startsWith(keys)).as("under " + keys).isTrue());
        assertThat(modes)
                .singleElement()
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try (var left = Files.list(keys)) {
            assertThat(left).as("nothing left of the copy").isEmpty();
        }
    }

    @Test
    void whatAnEarlierRunLeftIsRemovedAtTheStart() throws IOException {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                "XDG_RUNTIME_DIR".equals(name)
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        a -> 0);
        final Path keys = HostIdentity.derivations(context.paths());
        assertThat(keys.startsWith(context.paths().xdg().runtime()))
                .as("in the runtime directory, as written")
                .isTrue();
        final Path left = Files.createDirectories(keys.resolve("key-123")).resolve("key");
        Files.writeString(left, "-----BEGIN OPENSSH PRIVATE KEY-----");

        HostIdentity.clearDerivations(context.paths());

        assertThat(left).doesNotExist();
        assertThat(keys.resolve("key-123")).doesNotExist();
    }

    @Test
    void aMissingFieldIsRefused() {
        final Map<String, Object> identity = identity();
        at(identity, "repositories.0").remove("key_id");

        refused(identity, ORIGIN, "identity.repositories[0].key_id", "a repository without its id");
    }

    @Test
    void aControlCharacterIsRefusedInAnyTextAndALineBreakOutsideAPrivateKey() {
        final Map<String, Object> escape = identity();
        at(escape, "organization")
                .put("public", at(escape, "organization").get("public") + "\u001b[31m");
        refused(escape, ORIGIN, "identity.organization.public", "an escape in a public key");

        final Map<String, Object> newline = identity();
        at(newline, "repositories.0").put("project", "web\nother");
        refused(newline, ORIGIN, "identity.repositories[0].project", "a line break in a name");

        final Map<String, Object> inPrivate = identity();
        at(inPrivate, "ssh_host_keys.0")
                .put("private", at(inPrivate, "ssh_host_keys.0").get("private") + "\u0007");
        refused(
                inPrivate,
                ORIGIN,
                "identity.ssh_host_keys[0].private",
                "a bell in a private key, whose lines are its only breaks");
    }

    @Test
    void theHostIsTheEntrysOwn() {
        final Map<String, Object> identity = identity();
        identity.put("host", "other");

        refused(identity, ORIGIN, "identity.host", "another host's identity");
    }

    @Test
    void aTitleOtherThanTheOneTheMigrationRegistersIsRefused() {
        final Map<String, Object> identity = identity();
        at(identity, "repositories.0").put("title", "porelo host other project web");

        refused(identity, ORIGIN, "identity.repositories[0].title", "another host's title");
    }

    @Test
    void aKeyIdOfZeroIsRefused() {
        final Map<String, Object> identity = identity();
        at(identity, "repositories.0").put("key_id", 0L);

        refused(identity, ORIGIN, "identity.repositories[0].key_id", "a key never registered");
    }

    @Test
    void anAddressAtAnotherForgeOrOneThatCannotBeCheckedIsRefused() {
        final Map<String, Object> elsewhere = identity();
        at(elsewhere, "repositories.0").put("address", "https://forge.example.net/web/site.git");
        refused(
                elsewhere,
                ORIGIN,
                "identity.repositories[0].address",
                "an address outside the organization's forge");

        final Map<String, Object> withUser = identity();
        at(withUser, "repositories.0").put("address", "https://me:pw@forge.example/web/site.git");
        refused(
                withUser,
                ORIGIN,
                "identity.repositories[0].address",
                "an address carrying a credential");

        refused(
                identity(),
                null,
                "identity.repositories[0].address",
                "no organization, so no origin to check it against");
    }

    @Test
    void anSshAddressOnTheOrganizationsForgeIsTaken() {
        final Map<String, Object> identity = identity();
        at(identity, "repositories.0").put("address", "git@forge.example:web/site.git");

        assertThat(checked(identity, ORIGIN)).as("the value kept").isEqualTo(identity);
    }

    @Test
    void moreThan256RepositoriesAreRefused() {
        final Map<String, Object> identity = identity();
        final Map<String, Object> one = at(identity, "repositories.0");
        for (int each = 0; each < 256; each++) {
            list(identity, "repositories").add(one);
        }

        refused(identity, ORIGIN, "identity.repositories", "257 repositories");
    }

    @Test
    void aRepositoryNamedTwiceIsRefused() {
        final Map<String, Object> identity = identity();
        list(identity, "repositories").add(repository("web", "site", 8, ed25519("again")));

        refused(identity, ORIGIN, "identity.repositories[1].repository", "the same one twice");
    }

    @Test
    void noHostKeyOrFourAreRefused() {
        final Map<String, Object> none = identity();
        list(none, "ssh_host_keys").clear();
        refused(none, ORIGIN, "identity.ssh_host_keys", "no host key");

        final Map<String, Object> four = identity();
        for (int each = 0; each < 3; each++) {
            list(four, "ssh_host_keys").add(hostKey("ed25519", ed25519("more")));
        }
        refused(four, ORIGIN, "identity.ssh_host_keys", "four host keys");
    }

    @Test
    void aHostKeyTypeGivenTwiceOrNotTheKeysOwnIsRefused() {
        final Map<String, Object> twice = identity();
        list(twice, "ssh_host_keys").add(hostKey("ed25519", ed25519("second")));
        refused(twice, ORIGIN, "identity.ssh_host_keys[1].type", "ed25519 twice");

        final Map<String, Object> mislabeled = identity();
        at(mislabeled, "ssh_host_keys.0").put("type", "rsa");
        refused(mislabeled, ORIGIN, "identity.ssh_host_keys[0].type", "an Ed25519 key called rsa");

        final Map<String, Object> unknown = identity();
        at(unknown, "ssh_host_keys.0").put("type", "dsa");
        refused(unknown, ORIGIN, "identity.ssh_host_keys[0].type", "a type no host key has");
    }

    @Test
    void anEncryptedPrivateKeyIsRefused() throws Exception {
        final Map<String, Object> identity = identity();
        final Pair locked = keygen("locked", "-t", "ed25519", "-N", "a passphrase");
        at(identity, "organization").put("private", locked.privateKey());
        at(identity, "organization").put("public", locked.publicKey());

        refused(identity, ORIGIN, "identity.organization.private", "a key behind a passphrase");
    }

    @Test
    void aPrivateKeyWhosePublicHalfDiffersIsRefused() throws Exception {
        final Map<String, Object> organization = identity();
        at(organization, "organization").put("public", ed25519("other").publicKey());
        refused(organization, ORIGIN, "identity.organization.public", "another Ed25519 key");

        final Map<String, Object> ecdsa = identity();
        final Pair one = keygen("one", "-t", "ecdsa", "-N", "");
        final Pair other = keygen("other", "-t", "ecdsa", "-N", "");
        list(ecdsa, "ssh_host_keys")
                .add(hostKey("ecdsa", new Pair(one.privateKey(), other.publicKey())));
        refused(
                ecdsa,
                ORIGIN,
                "identity.ssh_host_keys[1].public",
                "an ECDSA key whose public half is another's");
    }

    @Test
    void aValueOver256KibIsRefusedWhole() {
        final Map<String, Object> identity = identity();
        at(identity, "organization")
                .put(
                        "public",
                        at(identity, "organization").get("public") + " " + "x".repeat(270_000));

        refused(identity, ORIGIN, "identity", "a value past 256 KiB");
    }

    @Test
    void theRefusalOfAMalformedKeyCarriesNoneOfIt() {
        final Map<String, Object> identity = identity();
        final String text = (String) at(identity, "repositories.0").get("private");
        assertThat(text).as("the key's magic, to damage").contains("b3BlbnNzaC1rZXktdjE");
        at(identity, "repositories.0")
                .put("private", text.replace("b3BlbnNzaC1rZXktdjE", "c3BlbnNzaC1rZXktdjE"));

        refused(identity, ORIGIN, "identity.repositories[0].private", "a damaged private key");
    }

    @Test
    void thePublicFormLeavesOutEveryPrivateField() {
        final Map<String, Object> shown = HostIdentity.publicOnly(checked(identity(), ORIGIN));

        assertThat(Json.write(shown))
                .as("no private field, no private key")
                .doesNotContain("\"private\"")
                .doesNotContain("PRIVATE KEY");
        assertThat(at(shown, "organization")).containsOnlyKeys("public");
        assertThat(at(shown, "repositories.0"))
                .containsOnlyKeys("project", "repository", "address", "key_id", "title", "public");
        assertThat(at(shown, "ssh_host_keys.0")).containsOnlyKeys("type", "public");
    }

    @Test
    void aKeptIdentityMayGainRepositoriesAndNothingElse() {
        final Map<String, Object> stored = checked(identity(), ORIGIN);

        final Map<String, Object> more = copy(stored);
        list(more, "repositories").add(repository("web", "docs", 9, ed25519("docs")));
        assertThat(HostIdentity.onlyAdds(stored, more)).as("one more repository").isTrue();
        assertThat(HostIdentity.onlyAdds(stored, copy(stored))).as("the same").isTrue();

        final Map<String, Object> withComment = copy(stored);
        at(withComment, "organization")
                .put(
                        "public",
                        String.join(
                                        " ",
                                        List.of(
                                                        ((String)
                                                                        at(stored, "organization")
                                                                                .get("public"))
                                                                .split(" "))
                                                .subList(0, 2))
                                + " renamed");
        assertThat(HostIdentity.onlyAdds(stored, withComment))
                .as("the same key under another comment")
                .isTrue();

        final Map<String, Object> organization = copy(stored);
        at(organization, "organization").putAll(key(ed25519("new")));
        assertThat(HostIdentity.onlyAdds(stored, organization))
                .as("another organization key")
                .isFalse();

        final Map<String, Object> hostKey = copy(stored);
        at(hostKey, "ssh_host_keys.0").putAll(hostKey("ed25519", ed25519("new")));
        assertThat(HostIdentity.onlyAdds(stored, hostKey)).as("another host key").isFalse();

        final Map<String, Object> extraHostKey = copy(stored);
        list(extraHostKey, "ssh_host_keys").add(hostKey("ecdsa", ed25519("x")));
        assertThat(HostIdentity.onlyAdds(stored, extraHostKey)).as("one more host key").isFalse();

        final Map<String, Object> dropped = copy(stored);
        list(dropped, "repositories").clear();
        assertThat(HostIdentity.onlyAdds(stored, dropped)).as("a repository dropped").isFalse();

        for (final String field : List.of("address", "key_id", "title", "public")) {
            final Map<String, Object> changed = copy(stored);
            at(changed, "repositories.0")
                    .put(
                            field,
                            switch (field) {
                                case "address" -> ORIGIN + "/web/moved.git";
                                case "key_id" -> 8L;
                                case "title" -> "porelo host other project web";
                                default -> ed25519("swapped").publicKey();
                            });
            assertThat(HostIdentity.onlyAdds(stored, changed))
                    .as("a repository's " + field + " changed")
                    .isFalse();
        }
    }

    @Test
    void theOrganizationsOriginIsReadFromItsSettings() throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_CONFIG_HOME".equals(name)
                                        ? dir.resolve("config").toString()
                                        : null,
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), a -> 0);
        assertThat(HostIdentity.centralOrigin(context)).as("no organization").isNull();

        final Path file = context.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "adapter: git\nsettings:\n  repository: git@Forge.Example:acme/org.git\n");

        assertThat(HostIdentity.centralOrigin(context)).isEqualTo(ORIGIN);
    }

    @Test
    void anEntrysNameIsTheHostsUnderItsOwnReservedPrefix() {
        assertThat(HostIdentity.entryName(HOST)).isEqualTo("host-identity/" + HOST);
        assertThat(VaultNames.reserved(HostIdentity.entryName(HOST))).isTrue();
        assertThat(TaskSecrets.reserved(HostIdentity.entryName(HOST))).isTrue();
        assertThat(
                        VaultNames.credentialsOnly(
                                Map.of(
                                        HostIdentity.entryName(HOST),
                                        org.fuin.porelo.vault.VaultEntry.of("{}"))))
                .isEmpty();
        assertThat(HostIdentity.validHost(HOST)).isTrue();
        assertThat(HostIdentity.validHost("Build_1")).isFalse();
        assertThat(HostIdentity.validHost("-x")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(final Map<String, Object> value) {
        return (Map<String, Object>) Json.parseExact(Json.write(value));
    }
}
