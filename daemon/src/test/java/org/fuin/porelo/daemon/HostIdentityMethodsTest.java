package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.HostVaultOwner;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.CredentialSeal;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for a host's identity kept in the person's vault over the daemon's socket: kept, read whole
 * or without its private halves, listed, and never reached by a task or under the name the vault's
 * own verbs take.
 */
class HostIdentityMethodsTest {

    private static final char[] PASSPHRASE = "fixture passphrase".toCharArray();

    private static final String HOST = "build-1";

    private static final String ENTRY = "host-identity/" + HOST;

    private static final List<String> METHODS =
            List.of("HostIdentityKeep", "HostIdentityRead", "HostIdentityPublic", "HostIdentities");

    @TempDir Path dir;

    private PoreloContext context;

    private PoreloContext context(final boolean asking) throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext made =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final Path organization = made.paths().xdg().config().resolve("organization.yml");
        Files.createDirectories(organization.getParent());
        Files.writeString(
                organization,
                "adapter: git\nsettings:\n  repository: git@forge.example:acme/org.git\n");
        return asking ? made.asking(prompt -> PASSPHRASE.clone()) : made;
    }

    private void openVault(final Map<String, VaultEntry> entries) throws Exception {
        context = context(true);
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(entries, PASSPHRASE);
        assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
    }

    @AfterEach
    void forgetThePassphrase() {
        if (context != null) {
            context.forgetTyped();
        }
    }

    private Map<String, Object> call(final String method, final Map<String, Object> parameters)
            throws Exception {
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context, socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    private VarlinkException refusal(final String method, final Map<String, Object> parameters) {
        return catchThrowableOfType(VarlinkException.class, () -> call(method, parameters));
    }

    private static Map<String, Object> key(final String comment) {
        final SigningKey key = SigningKey.generate(comment);
        final Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("public", key.authorizedKeysLine());
        pair.put("private", new String(key.openSshPrivateKey(), StandardCharsets.US_ASCII));
        return pair;
    }

    private static Map<String, Object> repository(final String name, final long id) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("project", "web");
        row.put("repository", name);
        row.put("address", "https://forge.example/web/" + name + ".git");
        row.put("key_id", id);
        row.put("title", "porelo host " + HOST + " project web");
        row.putAll(key(name));
        return row;
    }

    private static Map<String, Object> hostKey() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "ed25519");
        row.putAll(key("root@" + HOST));
        return row;
    }

    private static Map<String, Object> identity() {
        final Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("host", HOST);
        identity.put("organization", key("porelo host read key"));
        identity.put("repositories", new ArrayList<>(List.of(repository("site", 7))));
        identity.put("ssh_host_keys", new ArrayList<>(List.of(hostKey())));
        return identity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(final Map<String, Object> value) {
        return (Map<String, Object>) Json.parseExact(Json.write(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Map<String, Object> identity, final String field) {
        return (List<Object>) identity.get(field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(
            final Map<String, Object> identity, final String field) {
        return (Map<String, Object>) list(identity, field).get(0);
    }

    private Map<String, Object> keep(final Map<String, Object> identity, final boolean replace)
            throws Exception {
        return call(
                "HostIdentityKeep", Map.of("host", HOST, "identity", identity, "replace", replace));
    }

    private Object read() throws Exception {
        return call("HostIdentityRead", Map.of("host", HOST)).get("identity");
    }

    @Test
    void aFirstKeepIsReadBackWholeAndStoredOnlyEncrypted() throws Exception {
        openVault(Map.of());
        final Map<String, Object> identity = identity();

        assertThat(keep(identity, false)).as("nothing answered").isEmpty();

        assertThat(read()).as("the whole value").isEqualTo(identity);
        final VaultEntry stored = context.vault().read(PASSPHRASE).get(ENTRY);
        assertThat(stored.type()).isEqualTo("host-identity");
        final String body =
                ((String) first(identity, "repositories").get("private")).split("\n")[1];
        assertThat(Files.readString(context.vault().path(), StandardCharsets.ISO_8859_1))
                .as("the vault file holds no key in the clear")
                .doesNotContain(body)
                .doesNotContain(HOST);
    }

    @Test
    void theSameIdentityWithOneMoreRepositoryIsKept() throws Exception {
        openVault(Map.of());
        final Map<String, Object> identity = identity();
        keep(identity, false);
        final Map<String, Object> more = copy(identity);
        list(more, "repositories").add(repository("docs", 9));

        keep(more, false);

        assertThat(read()).isEqualTo(more);
    }

    @Test
    void aChangedKeyOrADroppedRepositoryIsRefusedUnlessReplaced() throws Exception {
        openVault(Map.of());
        final Map<String, Object> identity = identity();
        keep(identity, false);

        final Map<String, Object> organization = copy(identity);
        organization.put("organization", key("new"));
        final Map<String, Object> hostKey = copy(identity);
        hostKey.put("ssh_host_keys", List.of(hostKey()));
        final Map<String, Object> dropped = copy(identity);
        dropped.put("repositories", List.of(repository("docs", 9)));
        final Map<String, Object> changed = copy(identity);
        first(changed, "repositories").putAll(key("swapped"));

        Map<String, Object> last = identity;
        for (final Map<String, Object> each : List.of(organization, hostKey, dropped, changed)) {
            final VarlinkException refused =
                    catchThrowableOfType(VarlinkException.class, () -> keep(each, false));
            assertThat(refused.getErrorName())
                    .isEqualTo(PoreloDaemon.INTERFACE + ".HostIdentityExists");
            assertThat(refused.getParameters()).containsEntry("host", HOST);
            assertThat(read()).as("the kept identity stays").isEqualTo(last);

            keep(each, true);
            assertThat(read()).as("replaced at the person's word").isEqualTo(each);
            last = each;
        }
    }

    @Test
    void thePublicFormNamesAndUnknownHosts() throws Exception {
        openVault(Map.of("forge", VaultEntry.of("a-token")));
        final Map<String, Object> identity = identity();
        keep(identity, false);
        call(
                "HostIdentityKeep",
                Map.of("host", "build-2", "identity", withHost(identity(), "build-2")));

        final Object shown = call("HostIdentityPublic", Map.of("host", HOST)).get("identity");

        assertThat(Json.write(shown))
                .doesNotContain("\"private\"")
                .doesNotContain("PRIVATE KEY")
                .contains((String) first(identity, "ssh_host_keys").get("public"));
        assertThat(call("HostIdentities", Map.of()))
                .containsEntry("hosts", List.of(HOST, "build-2").stream().sorted().toList());
        for (final String method : List.of("HostIdentityRead", "HostIdentityPublic")) {
            final VarlinkException unknown = refusal(method, Map.of("host", "build-3"));
            assertThat(unknown.getErrorName())
                    .isEqualTo(PoreloDaemon.INTERFACE + ".HostIdentityUnknown");
            assertThat(unknown.getParameters()).containsEntry("host", "build-3");
        }
    }

    private static Map<String, Object> withHost(
            final Map<String, Object> identity, final String host) {
        identity.put("host", host);
        for (final Object row : list(identity, "repositories")) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> repository = (Map<String, Object>) row;
            repository.put("title", "porelo host " + host + " project web");
        }
        return identity;
    }

    @Test
    void aShutVaultAnswersItsLockedError() throws Exception {
        openVault(Map.of());
        keep(identity(), false);
        context.forgetTyped();
        context = context(false);
        assertThat(context.opener()).as("the fixture's vault is shut").isEmpty();

        for (final String method : METHODS) {
            final VarlinkException locked =
                    refusal(
                            method,
                            "HostIdentityKeep".equals(method)
                                    ? Map.of("host", HOST, "identity", identity())
                                    : Map.of("host", HOST));
            assertThat(locked.getErrorName())
                    .as(method)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".VaultLocked");
        }
    }

    @Test
    void aHostsVaultNeverHandsOutAPrivateSet() throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            context = context(false).withHostVault(owner);
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), new byte[32]);
            owner.keep(new byte[32]);
            assertThat(context.vault().isHostVault()).isTrue();
            // Called as the socket calls them: a served socket forgets the share when it closes.
            final Map<String, Object> host = Map.of("host", HOST);
            VaultMethods.hostIdentityKeep(context, Map.of("host", HOST, "identity", identity()));

            final VarlinkException refused =
                    catchThrowableOfType(
                            VarlinkException.class,
                            () -> VaultMethods.hostIdentity(context, host, true));

            assertThat(refused.getErrorName())
                    .isEqualTo(PoreloDaemon.INTERFACE + ".HostIdentityNotReadOnAHost");
            assertThat(VaultMethods.hostIdentity(context, host, false))
                    .as("the public form, which a host's vault may answer")
                    .containsKey("identity");
        }
    }

    @Test
    void aMalformedValueIsRefusedNamingItsFieldAndNoKey() throws Exception {
        openVault(Map.of());
        final Map<String, Object> identity = identity();
        first(identity, "repositories").put("key_id", 0L);

        final VarlinkException refused =
                refusal("HostIdentityKeep", Map.of("host", HOST, "identity", identity));

        assertThat(refused.getErrorName()).isEqualTo("org.varlink.service.InvalidParameter");
        assertThat(refused.getParameters())
                .containsEntry("parameter", "identity.repositories[0].key_id");
        assertThat(Json.write(refused.getParameters()) + refused.getMessage())
                .doesNotContain("PRIVATE KEY")
                .doesNotContain("AAAA");
        assertThat(context.vault().read(PASSPHRASE)).doesNotContainKey(ENTRY);

        final VarlinkException host = refusal("HostIdentityRead", Map.of("host", "../" + HOST));
        assertThat(host.getErrorName()).isEqualTo("org.varlink.service.InvalidParameter");
        assertThat(host.getParameters()).containsEntry("parameter", "host");
    }

    @Test
    void aTaskReachesNoHostIdentityMethod() throws Exception {
        openVault(Map.of());
        keep(identity(), false);

        assertThat(McpMethods.NEVER).containsAll(METHODS);
        final Map<String, Object> keep = Map.of("host", HOST, "identity", identity());
        final Map<String, Object> read = Map.of("host", HOST);
        final List<Runnable> calls =
                List.of(
                        () -> VaultMethods.hostIdentityKeep(context, keep),
                        () -> VaultMethods.hostIdentity(context, read, true),
                        () -> VaultMethods.hostIdentity(context, read, false),
                        () -> VaultMethods.hostIdentities(context));
        for (final Runnable each : calls) {
            McpTaskSockets.as(
                    "porelo-web-lead",
                    () -> {
                        final VarlinkException refused =
                                catchThrowableOfType(VarlinkException.class, each::run);
                        assertThat(refused).as("refused on a task's socket").isNotNull();
                        assertThat(refused.getErrorName())
                                .isEqualTo("org.varlink.service.MethodNotFound");
                    });
        }
        assertThat(VaultMethods.hostIdentity(context, read, true))
                .as("the person's own call is answered")
                .containsKey("identity");
    }

    @Test
    void theVaultsOwnVerbsRefuseTheReservedNameAndCredentialsDoNotListIt() throws Exception {
        openVault(Map.of());
        keep(identity(), false);
        final VaultEntry kept = context.vault().read(PASSPHRASE).get(ENTRY);

        for (final Map.Entry<String, Map<String, Object>> verb :
                Map.of(
                                "CredentialRemove",
                                Map.<String, Object>of("name", ENTRY),
                                "CredentialReplace",
                                Map.<String, Object>of("name", ENTRY, "value", "x"))
                        .entrySet()) {
            final VarlinkException refused = refusal(verb.getKey(), verb.getValue());
            assertThat(refused.getErrorName())
                    .isEqualTo(PoreloDaemon.INTERFACE + ".CredentialRefused");
            assertThat(refused.getParameters()).containsEntry("reason", "RESERVED");
        }
        assertThat(
                        CredentialCarrying.seal(
                                context,
                                Map.of(
                                        "key",
                                        CredentialSeal.receiver().publicKey(),
                                        "name",
                                        ENTRY)))
                .containsEntry("outcome", "RESERVED")
                .containsEntry("sealed", "");

        final CredentialCarrying.Receiving receiving =
                new CredentialCarrying.Receiving(java.time.Clock::systemUTC);
        final String receiver = (String) receiving.receiver(context).get("key");
        final String sealed =
                CredentialSeal.seal(
                        receiver, ENTRY, new VaultEntry("{}", "host-identity", Map.of()));
        assertThat(
                        receiving.receive(
                                context,
                                Map.of(
                                        "sealed",
                                        sealed,
                                        "name",
                                        ENTRY,
                                        "kind",
                                        "host-identity",
                                        "replace",
                                        true)))
                .containsEntry("outcome", "RESERVED");

        assertThat(context.vault().read(PASSPHRASE).get(ENTRY))
                .as("the identity is untouched")
                .isEqualTo(kept);
        assertThat(Json.write(call("Credentials", Map.of())))
                .as("no credential row names it")
                .doesNotContain("host-identity");
    }
}
