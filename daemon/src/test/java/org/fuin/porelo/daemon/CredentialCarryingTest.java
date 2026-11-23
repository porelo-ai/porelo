package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.agent.api.Agent;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.agent.api.AgentServer;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.agent.api.CredentialExtractor;
import org.fuin.porelo.app.CredentialImport;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A vault entry carried from this machine's daemon to a host's, sealed to a key the host's daemon
 * made for that one copy: what the window carries between them is ciphertext only.
 */
class CredentialCarryingTest {

    private static final char[] PASSPHRASE = "fixture passphrase".toCharArray();

    private static final String VALUE = "fixture-value-7c1e";

    private static final VaultEntry ENTRY =
            new VaultEntry(VALUE, "api-key", Map.of("expires", "2027-01-01"));

    @TempDir Path dir;

    private final Instant now = Instant.parse("2026-11-14T16:00:00Z");

    private Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private PoreloContext here;

    private PoreloContext host;

    private PoreloContext context(final String name) {
        final Path home = dir.resolve(name);
        final XdgPaths xdg =
                XdgPaths.of(
                        variable ->
                                switch (variable) {
                                    case "XDG_CONFIG_HOME" -> home.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> home.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> home.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> home.resolve("run").toString();
                                    default -> null;
                                },
                        home);
        // The shorter way to the agents' sockets, so they fit the Unix limit wherever the
        // checkout lies.
        final XdgPaths shortSockets =
                new XdgPaths(
                        xdg.config(), xdg.data(), xdg.state(), shortest(xdg.runtime()), xdg.home());
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(shortSockets, home.resolve("bin")),
                        arguments -> 0)
                .asking(prompt -> PASSPHRASE.clone());
    }

    private static Path shortest(final Path directory) {
        Path from = Path.of("").toAbsolutePath();
        try {
            from = from.toRealPath();
        } catch (final java.io.IOException ex) {
            // Taken as written.
        }
        final Path relative = from.relativize(directory);
        return relative.toString().length() < directory.toString().length() ? relative : directory;
    }

    /** A vault holding the entries, opened the way a person at the machine opened it. */
    private PoreloContext unlocked(final String name, final Map<String, VaultEntry> entries)
            throws Exception {
        final PoreloContext made = context(name);
        Files.createDirectories(made.vault().path().getParent());
        made.vault().write(entries, PASSPHRASE);
        assertThat(made.openerAsking(false, new PrintWriter(Writer.nullWriter()))).isPresent();
        return made;
    }

    @AfterEach
    void forget() {
        for (final PoreloContext each : new PoreloContext[] {here, host}) {
            if (each != null) {
                each.forgetTyped();
            }
        }
    }

    private CredentialCarrying.Receiving receiving() {
        return new CredentialCarrying.Receiving(() -> clock);
    }

    @Test
    void anEntryCarriedSealedIsStoredOnTheHostAsItWasHere() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final CredentialCarrying.Receiving receiving = receiving();

        final Map<String, Object> key = receiving.receiver(host);
        final Map<String, Object> sealed =
                CredentialCarrying.seal(here, Map.of("key", key.get("key"), "name", "anthropic"));
        final Map<String, Object> received =
                receiving.receive(
                        host,
                        Map.of(
                                "sealed", sealed.get("sealed"),
                                "name", sealed.get("name"),
                                "kind", sealed.get("kind"),
                                "replace", false));

        assertThat(key.get("until")).isEqualTo("2026-11-14T16:05:00Z");
        assertThat(sealed)
                .containsEntry("outcome", "SEALED")
                .containsEntry("name", "anthropic")
                .containsEntry("kind", "api-key");
        assertThat(sealed.toString()).doesNotContain(VALUE);
        assertThat(received).containsEntry("outcome", "STORED");
        assertThat(received.toString()).doesNotContain(VALUE);
        assertThat(host.vault().read(PASSPHRASE)).containsEntry("anthropic", ENTRY);
    }

    @Test
    void aKeyOpensOneBlobOnceAndAStrangeBlobEndsNoKey() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final CredentialCarrying.Receiving receiving = receiving();
        final Object key = receiving.receiver(host).get("key");
        final Map<String, Object> sealed =
                CredentialCarrying.seal(here, Map.of("key", key, "name", "anthropic"));
        final Map<String, Object> carried =
                Map.of(
                        "sealed",
                        sealed.get("sealed"),
                        "name",
                        "anthropic",
                        "kind",
                        "api-key",
                        "replace",
                        false);

        assertThat(
                        receiving.receive(
                                host,
                                Map.of(
                                        "sealed",
                                        "AAAA",
                                        "name",
                                        "anthropic",
                                        "kind",
                                        "api-key",
                                        "replace",
                                        false)))
                .containsEntry("outcome", "REFUSED");
        assertThat(receiving.receive(host, carried)).containsEntry("outcome", "STORED");
        assertThat(receiving.receive(host, carried)).containsEntry("outcome", "NO_RECEIVER");
    }

    @Test
    void aReceivingKeyAtItsFiveMinutesIsGone() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final CredentialCarrying.Receiving receiving = receiving();
        final Object key = receiving.receiver(host).get("key");
        final Map<String, Object> sealed =
                CredentialCarrying.seal(here, Map.of("key", key, "name", "anthropic"));
        clock = Clock.fixed(now.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);

        assertThat(
                        receiving.receive(
                                host,
                                Map.of(
                                        "sealed",
                                        sealed.get("sealed"),
                                        "name",
                                        "anthropic",
                                        "kind",
                                        "api-key",
                                        "replace",
                                        false)))
                .containsEntry("outcome", "NO_RECEIVER");
    }

    @Test
    void aBlobCarriedUnderAnotherNameIsRefusedAndStoresNothing() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final CredentialCarrying.Receiving receiving = receiving();
        final Object key = receiving.receiver(host).get("key");
        final Map<String, Object> sealed =
                CredentialCarrying.seal(here, Map.of("key", key, "name", "anthropic"));

        assertThat(
                        receiving.receive(
                                host,
                                Map.of(
                                        "sealed",
                                        sealed.get("sealed"),
                                        "name",
                                        "openai",
                                        "kind",
                                        "api-key",
                                        "replace",
                                        false)))
                .containsEntry("outcome", "REFUSED");
        assertThat(host.vault().read(PASSPHRASE)).isEmpty();
    }

    @Test
    void anEntryAlreadyOnTheHostIsKeptUnlessReplaced() throws Exception {
        final VaultEntry older = new VaultEntry("older-value", "api-key", Map.of());
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of("anthropic", older));
        final CredentialCarrying.Receiving receiving = receiving();

        for (final boolean replace : new boolean[] {false, true}) {
            final Object key = receiving.receiver(host).get("key");
            final Map<String, Object> sealed =
                    CredentialCarrying.seal(here, Map.of("key", key, "name", "anthropic"));
            final Map<String, Object> received =
                    receiving.receive(
                            host,
                            Map.of(
                                    "sealed",
                                    sealed.get("sealed"),
                                    "name",
                                    "anthropic",
                                    "kind",
                                    "api-key",
                                    "replace",
                                    replace));
            assertThat(received).containsEntry("outcome", replace ? "STORED" : "EXISTS");
            assertThat(host.vault().read(PASSPHRASE).get("anthropic"))
                    .isEqualTo(replace ? ENTRY : older);
        }
    }

    @Test
    void aSealOfAnEntryThatIsNotThereOrToNoKeySaysSo() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final Object key = receiving().receiver(host).get("key");

        assertThat(CredentialCarrying.seal(here, Map.of("key", key, "name", "openai")))
                .containsEntry("outcome", "NO_SUCH_ENTRY")
                .containsEntry("sealed", "");
        assertThat(
                        CredentialCarrying.seal(
                                here, Map.of("key", "bm90IGEga2V5", "name", "anthropic")))
                .containsEntry("outcome", "BAD_KEY")
                .containsEntry("sealed", "");
    }

    @Test
    void aLockedVaultMakesNoKeyAndSealsNothing() throws Exception {
        here = context("here");
        Files.createDirectories(here.vault().path().getParent());
        here.vault().write(Map.of("anthropic", ENTRY), PASSPHRASE);
        host = context("host");
        Files.createDirectories(host.vault().path().getParent());
        host.vault().write(Map.of(), PASSPHRASE);

        assertThat(receiving().receiver(host)).containsEntry("outcome", "VAULT_LOCKED");
        assertThat(
                        CredentialCarrying.seal(
                                here,
                                Map.of(
                                        "key",
                                        org.fuin.porelo.vault.CredentialSeal.receiver().publicKey(),
                                        "name",
                                        "anthropic")))
                .containsEntry("outcome", "VAULT_LOCKED");
    }

    private static final String SIGN_IN = "fixture-sign-in-5d2a";

    /** A test agent whose own file holds a sign-in for alpha, or none, as a file beside it says. */
    public static final class SignedIn {

        /**
         * Serves the agent.
         *
         * @param args Definition file, the limits option and its line, serve verb and socket path.
         * @throws java.io.IOException If a file cannot be read.
         */
        public static void main(final String[] args) throws java.io.IOException {
            final AgentDefinition definition =
                    AgentDefinitionReader.read(
                            new java.io.StringReader(Files.readString(Path.of(args[0]))), args[0]);
            final String behavior = Files.readString(Path.of(args[0] + ".behavior")).strip();
            final boolean holds = behavior.equals("holds");
            final CredentialExtractor extractor =
                    new CredentialExtractor() {
                        @Override
                        public Optional<Credential> extract(final Path directory) {
                            return extract(directory, null);
                        }

                        @Override
                        public Optional<Credential> extract(
                                final Path directory, final @Nullable String provider) {
                            if (behavior.equals("throws")) {
                                // An agent whose failure quotes what it read.
                                throw new IllegalArgumentException("unreadable: " + SIGN_IN);
                            }
                            return holds && (provider == null || provider.equals("alpha"))
                                    ? Optional.of(
                                            Credential.of("api-key", SIGN_IN).forProvider("alpha"))
                                    : Optional.empty();
                        }
                    };
            try (AgentServer server =
                    new AgentServer(
                            new Agent() {
                                @Override
                                public AgentDefinition definition() {
                                    return definition;
                                }

                                @Override
                                public CredentialExtractor credentialExtractor() {
                                    return extractor;
                                }
                            },
                            Path.of(args[args.length - 1]))) {
                System.out.println("ready " + args[args.length - 1]);
                System.out.flush();
                server.serve();
            }
        }
    }

    /** Installs the test agent and the provider alpha on a machine. */
    private void installed(final PoreloContext machine, final String behavior) throws Exception {
        final Path providers =
                Files.createDirectories(machine.paths().xdg().data().resolve("providers"));
        Files.writeString(
                providers.resolve("alpha.yaml"),
                """
                name: alpha
                upstream: https://alpha.example.com
                dialects: { preview: "" }
                auth_header: { _default: Authorization }
                auth_prefix: { _default: "Bearer " }
                """);
        final Path agents = Files.createDirectories(machine.paths().xdg().data().resolve("agents"));
        final Path definition = agents.resolve("holder.yaml");
        Files.writeString(
                definition,
                """
                name: holder
                binary: holder
                git_identity: { name: Holder, email: holder@example.com }
                headless: { prompt_flag: "-p" }
                allowed_domains: [agent.example.com]
                provider:
                  dialect: preview
                  default: alpha
                  endpoint: url
                  base_url_env: HOLDER_BASE_URL
                """);
        Files.writeString(Path.of(definition + ".behavior"), behavior);
        final Path executable = agents.resolve(AgentDirectory.PREFIX + "holder");
        Files.writeString(
                executable,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + SignedIn.class.getName()
                        + "' '"
                        + definition
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }

    private Map<String, Object> sealSignIn(final Object key, final String behavior)
            throws Exception {
        installed(here, behavior);
        return CredentialCarrying.seal(
                here,
                Map.of(
                        "key",
                        key,
                        "agent",
                        "holder",
                        "provider",
                        "alpha",
                        "configDirectory",
                        dir.resolve("agent-config").toString()));
    }

    @Test
    void anAgentsSignInIsReceivedOnTheHostUnderTheKeyAnImportUsesAndNothingIsWrittenHere()
            throws Exception {
        here = unlocked("here", Map.of("other", ENTRY));
        host = unlocked("host", Map.of());
        final byte[] before = Files.readAllBytes(here.vault().path());
        final CredentialCarrying.Receiving receiving = receiving();

        final Map<String, Object> sealed = sealSignIn(receiving.receiver(host).get("key"), "holds");
        final Map<String, Object> received =
                receiving.receive(
                        host,
                        Map.of(
                                "sealed", sealed.get("sealed"),
                                "name", sealed.get("name"),
                                "kind", sealed.get("kind"),
                                "replace", false));

        assertThat(sealed)
                .containsEntry("outcome", "SEALED")
                .containsEntry("kind", "api-key")
                .containsEntry("detail", "");
        assertThat(sealed.toString()).doesNotContain(SIGN_IN);
        assertThat(received).containsEntry("outcome", "STORED");
        assertThat(Files.readAllBytes(here.vault().path()))
                .as("this machine's vault is neither written nor rewritten")
                .isEqualTo(before);

        // What an import here stores, and under which key, is what the host received.
        final CredentialImport.Result imported =
                CredentialImport.run(
                        here,
                        "holder",
                        dir.resolve("agent-config").toString(),
                        "alpha",
                        here::opener);
        assertThat(imported.outcome()).isEqualTo(CredentialImport.Outcome.IMPORTED);
        assertThat(sealed).containsEntry("name", imported.name());
        final VaultEntry stored = host.vault().read(PASSPHRASE).get(imported.name());
        assertThat(stored).isNotNull();
        assertThat(stored.value()).isEqualTo(SIGN_IN);
        assertThat(stored).isEqualTo(here.vault().read(PASSPHRASE).get(imported.name()));
    }

    @Test
    void anAgentsSignInIsSealedWhileThisMachinesVaultIsShut() throws Exception {
        // The sign-in is read from the agent's own file; the vault here is never opened.
        here = context("here");
        Files.createDirectories(here.vault().path().getParent());
        here.vault().write(Map.of("other", ENTRY), PASSPHRASE);
        host = unlocked("host", Map.of());

        assertThat(sealSignIn(receiving().receiver(host).get("key"), "holds"))
                .containsEntry("outcome", "SEALED")
                .containsEntry("name", "alpha");
    }

    @Test
    void anAgentWithNoSignInAnswersNothingToImportAndNoBlob() throws Exception {
        here = unlocked("here", Map.of());
        host = unlocked("host", Map.of());

        final Map<String, Object> sealed =
                sealSignIn(receiving().receiver(host).get("key"), "none");

        assertThat(sealed)
                .containsEntry("outcome", "NOTHING_TO_IMPORT")
                .containsEntry("sealed", "");
        assertThat(sealed.get("detail")).asString().isNotBlank();
    }

    @Test
    void aBlankProviderBesideAnAgentSealsNothing() throws Exception {
        // Read as none, it would seal whatever the agent answers; ImportCredential refuses it too.
        here = unlocked("here", Map.of());
        host = unlocked("host", Map.of());
        final Object key = receiving().receiver(host).get("key");
        installed(here, "holds");

        final Map<String, Object> sealed =
                CredentialCarrying.seal(
                        here,
                        Map.of(
                                "key",
                                key,
                                "agent",
                                "holder",
                                "provider",
                                "",
                                "configDirectory",
                                dir.resolve("agent-config").toString()));

        assertThat(sealed).containsEntry("outcome", "WRONG_PROVIDER").containsEntry("sealed", "");
    }

    @Test
    void aBlankProviderAloneAsksForASignInAndIsRefused() throws Exception {
        here = unlocked("here", Map.of("alpha", ENTRY));
        host = unlocked("host", Map.of());
        final Object key = receiving().receiver(host).get("key");
        installed(here, "holds");

        assertThat(
                        CredentialCarrying.seal(
                                here,
                                Map.of(
                                        "key",
                                        key,
                                        "provider",
                                        "",
                                        "configDirectory",
                                        dir.resolve("agent-config").toString())))
                .containsEntry("outcome", "WRONG_PROVIDER")
                .containsEntry("sealed", "");
        assertThat(
                        CredentialCarrying.seal(
                                here, Map.of("key", key, "name", "alpha", "provider", "")))
                .containsEntry("outcome", "FAILED")
                .containsEntry("sealed", "");
    }

    @Test
    void anAgentsFailureIsSaidWithoutItsWords() throws Exception {
        here = unlocked("here", Map.of());
        host = unlocked("host", Map.of());

        final Map<String, Object> sealed =
                sealSignIn(receiving().receiver(host).get("key"), "throws");

        assertThat(sealed).containsEntry("outcome", "FAILED").containsEntry("sealed", "");
        assertThat(sealed.toString()).doesNotContain(SIGN_IN);
    }

    @Test
    void aNameAndAnAgentTogetherAreRefusedAndSealNothing() throws Exception {
        here = unlocked("here", Map.of("anthropic", ENTRY));
        host = unlocked("host", Map.of());
        final Object key = receiving().receiver(host).get("key");

        final Map<String, Object> sealed =
                CredentialCarrying.seal(
                        here, Map.of("key", key, "name", "anthropic", "agent", "holder"));

        assertThat(sealed).containsEntry("outcome", "FAILED").containsEntry("sealed", "");
        assertThat(sealed.get("detail")).asString().contains("not both");
    }

    @Test
    void theImportableSignInsAreListedByNameOnly() throws Exception {
        here = context("here");
        installed(here, "holds");
        Files.writeString(
                here.paths().xdg().data().resolve("agents/holder.yaml"),
                Files.readString(here.paths().xdg().data().resolve("agents/holder.yaml"))
                        + "config_dir: "
                        + dir.resolve("agent-config")
                        + "\n");

        assertThat(CredentialImport.importable(here))
                .containsExactly(new CredentialImport.Importable("holder", "alpha", "api-key"));
    }
}
