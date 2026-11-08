package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.agent.api.Agent;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.agent.api.AgentServer;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.agent.api.CredentialExtractor;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that an imported credential lands under the provider it is for, over the real agent
 * protocol: stored under the wrong name, it is used for the wrong provider, or silently not found.
 */
class CredentialImportTest {

    private static final char[] PASSPHRASE = "a passphrase for this test".toCharArray();

    private static final String ALPHA_VALUE = "value-held-for-alpha-0123456789";

    private static final String BETA_VALUE = "value-held-for-beta-0123456789";

    @TempDir private Path dir;

    private PoreloContext context;

    /** A test agent whose store is what its behavior, read from a file beside it, says. */
    public static final class Holder {

        /**
         * Serves the agent.
         *
         * @param args Definition file, serve verb and socket path.
         * @throws IOException If a file cannot be read.
         */
        public static void main(final String[] args) throws IOException {
            final AgentDefinition definition =
                    AgentDefinitionReader.read(
                            new StringReader(Files.readString(Path.of(args[0]))), args[0]);
            final CredentialExtractor held =
                    extractor(Files.readString(Path.of(args[0] + ".behavior")).strip());
            final CredentialExtractor extractor =
                    new Answering() {
                        @Override
                        public Optional<Credential> extract(
                                final Path directory, final @Nullable String provider) {
                            try {
                                Files.writeString(Path.of(args[0] + ".asked"), "asked");
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                            return held.extract(directory, provider);
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
                            Path.of(args[2]))) {
                System.out.println("ready " + args[2]);
                System.out.flush();
                server.serve();
            }
        }

        private static Credential alpha() {
            return Credential.of("api-key", ALPHA_VALUE).forProvider("alpha");
        }

        private static Credential beta() {
            return Credential.of("api-key", BETA_VALUE).forProvider("beta");
        }

        private static CredentialExtractor extractor(final String behavior) {
            return switch (behavior) {
                case "holds-both" ->
                        new Answering() {
                            @Override
                            public Optional<Credential> extract(
                                    final Path directory, final @Nullable String provider) {
                                return "beta".equals(provider)
                                        ? Optional.of(beta())
                                        : Optional.of(alpha());
                            }
                        };
                case "holds-alpha" ->
                        new Answering() {
                            @Override
                            public Optional<Credential> extract(
                                    final Path directory, final @Nullable String provider) {
                                return provider == null || provider.equals("alpha")
                                        ? Optional.of(alpha())
                                        : Optional.empty();
                            }
                        };
                case "answers-alpha-whatever-is-asked", "names-beta" ->
                        new Answering() {
                            @Override
                            public Optional<Credential> extract(
                                    final Path directory, final @Nullable String provider) {
                                return Optional.of(
                                        behavior.equals("names-beta") ? beta() : alpha());
                            }
                        };
                case "names-one-not-declared" ->
                        directory ->
                                Optional.of(
                                        Credential.of("api-key", ALPHA_VALUE)
                                                .forProvider("../elsewhere"));
                case "names-a-line-break" ->
                        directory ->
                                Optional.of(
                                        Credential.of("api-key", ALPHA_VALUE)
                                                .forProvider("alpha\nforged line"));
                case "older" -> directory -> Optional.of(Credential.of("api-key", ALPHA_VALUE));
                default -> throw new IllegalArgumentException(behavior);
            };
        }

        /** An extractor that answers the question with a provider, and only that one. */
        private abstract static class Answering implements CredentialExtractor {

            @Override
            public Optional<Credential> extract(final Path directory) {
                return extract(directory, null);
            }

            @Override
            public abstract Optional<Credential> extract(Path directory, @Nullable String provider);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        // The shorter way to the sockets, so they fit the Unix limit wherever the checkout lies.
        final XdgPaths shortSockets =
                new XdgPaths(
                        xdg.config(),
                        xdg.data(),
                        xdg.state(),
                        SocketPaths.shortest(xdg.runtime()),
                        xdg.home());
        context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(shortSockets, dir.resolve("bin")),
                        command -> 0);
        final Path providers = Files.createDirectories(xdg.data().resolve("providers"));
        for (final String name : new String[] {"alpha", "beta"}) {
            Files.writeString(providers.resolve(name + ".yaml"), provider(name, "preview"));
        }
        Files.writeString(providers.resolve("gamma.yaml"), provider("gamma", "another"));
        context.vault().write(new LinkedHashMap<>(), PASSPHRASE);
    }

    private static String provider(final String name, final String dialect) {
        return """
        name: %s
        upstream: https://%s.example.com
        dialects: { %s: "" }
        auth_header: { _default: Authorization }
        auth_prefix: { _default: "Bearer " }
        """
                .formatted(name, name, dialect);
    }

    private void installed(final String behavior) throws IOException {
        final Path agents = Files.createDirectories(context.paths().xdg().data().resolve("agents"));
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
                        + Holder.class.getName()
                        + "' '"
                        + definition
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }

    private CredentialImport.Result importing(final @Nullable String provider) {
        return CredentialImport.run(
                context,
                "holder",
                dir.resolve("agent-config").toString(),
                provider,
                () -> Optional.of(VaultFile.Opener.passphrase(PASSPHRASE)));
    }

    private boolean agentWasAsked() {
        return Files.exists(context.paths().xdg().data().resolve("agents/holder.yaml.asked"));
    }

    private Map<String, VaultEntry> vault() {
        return context.vault().read(PASSPHRASE);
    }

    private void assertNothingStored(final CredentialImport.Result result) {
        assertThat(result.name()).as("no name reported for what was not stored").isEmpty();
        assertThat(vault()).as("a refused import leaves the vault as it was").isEmpty();
        assertThat(result.detail())
                .as("a refusal never says the value it refused")
                .doesNotContain(ALPHA_VALUE, BETA_VALUE);
    }

    @Test
    void theProviderNamedIsStoredUnderItsNameAndTheOtherIsLeftAlone() throws IOException {

        installed("holds-both");
        final Map<String, VaultEntry> before = new LinkedHashMap<>();
        before.put("alpha", new VaultEntry("the-vaults-own-alpha-0123456789", "api-key"));
        context.vault().write(before, PASSPHRASE);

        final CredentialImport.Result result = importing("beta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.IMPORTED);
        assertThat(result.name()).isEqualTo("beta");
        assertThat(vault().get("beta").value())
                .as("the sign-in asked for, under its own name")
                .isEqualTo(BETA_VALUE);
        assertThat(vault().get("alpha").value())
                .as("the other provider's credential is untouched")
                .isEqualTo("the-vaults-own-alpha-0123456789");
    }

    @Test
    void aProviderTheAgentHoldsNoSignInForStoresNothing() throws IOException {

        installed("holds-alpha");

        final CredentialImport.Result result = importing("beta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.NOTHING_TO_IMPORT);
        assertNothingStored(result);
    }

    @Test
    void anAnswerForAnotherProviderIsRefused() throws IOException {

        installed("answers-alpha-whatever-is-asked");

        final CredentialImport.Result result = importing("beta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertThat(result.detail()).contains("alpha", "beta");
        assertNothingStored(result);
    }

    @Test
    void anAgentThatCannotSayWhichProviderItAnsweredIsRefusedWhenAskedForOne() throws IOException {

        // An agent built before the parameter ignores it, and what it answers may be anybody's.
        installed("older");

        final CredentialImport.Result result = importing("beta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertNothingStored(result);
    }

    @Test
    void anAgentBuiltBeforeTheParameterIsImportedAsBeforeWhenNoneIsAskedFor() throws IOException {

        installed("older");

        final CredentialImport.Result result = importing(null);

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.IMPORTED);
        assertThat(vault().get("alpha").value())
                .as("under the agent's default, where it has always gone")
                .isEqualTo(ALPHA_VALUE);
    }

    @Test
    void withNoneAskedForAnAnswerNamingItsProviderIsStoredUnderThatOne() throws IOException {

        installed("names-beta");

        final CredentialImport.Result result = importing(null);

        assertThat(result.name()).isEqualTo("beta");
        assertThat(vault().keySet())
                .as("under the provider it is for, never the default as well")
                .containsExactly("beta");
    }

    @Test
    void anAnswerNamingAProviderNotDeclaredHereIsRefusedRatherThanStoredUnderTheDefault()
            throws IOException {

        // What the agent names comes from outside: it may not become a vault key unread.
        installed("names-one-not-declared");

        final CredentialImport.Result result = importing(null);

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertNothingStored(result);
    }

    @Test
    void aProviderNotDeclaredHereIsRefusedBeforeTheAgentIsAsked() throws IOException {

        installed("holds-both");

        final CredentialImport.Result result = importing("delta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertNothingStored(result);
        assertThat(agentWasAsked()).as("nothing of the agent's store was read for it").isFalse();
    }

    @Test
    void aProviderTheAgentCannotDriveIsRefused() throws IOException {

        // Stored, it would sit under a name no task of this agent could use.
        installed("holds-both");

        final CredentialImport.Result result = importing("gamma");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertNothingStored(result);
        assertThat(agentWasAsked()).as("nothing of the agent's store was read for it").isFalse();
    }

    private void holdsBetaWithItsGrant() {
        final Map<String, VaultEntry> before = new LinkedHashMap<>();
        before.put("beta", new VaultEntry("the-persons-own-beta-0123456789", "api-key"));
        before.put("grant/beta", new VaultEntry("the-persons-own-grant-0123456789", "refresh"));
        context.vault().write(before, PASSPHRASE);
    }

    @Test
    void anAgentsWordAloneNeverReplacesAnotherProvidersEntryOrItsGrant() throws IOException {

        // A wrong hint in the agent's config file would put away the person's own key, and send the
        // foreign secret to that provider's host.
        installed("names-beta");
        holdsBetaWithItsGrant();

        final CredentialImport.Result result = importing(null);

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertThat(result.detail()).as("how to mean it").contains("--provider beta");
        assertThat(vault().get("beta").value()).isEqualTo("the-persons-own-beta-0123456789");
        assertThat(vault().get("grant/beta").value()).isEqualTo("the-persons-own-grant-0123456789");
    }

    @Test
    void aPersonNamingTheProviderReplacesItsEntry() throws IOException {

        installed("holds-both");
        holdsBetaWithItsGrant();

        final CredentialImport.Result result = importing("beta");

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.IMPORTED);
        assertThat(vault().get("beta").value()).isEqualTo(BETA_VALUE);
    }

    @Test
    void aProviderNameThatCannotBeOneIsRefusedAndNotRepeated() throws IOException {

        // What it sent would otherwise be printed, and answered as the detail over the socket.
        installed("names-a-line-break");

        final CredentialImport.Result result = importing(null);

        assertThat(result.outcome()).isEqualTo(CredentialImport.Outcome.WRONG_PROVIDER);
        assertThat(result.detail()).doesNotContain("forged line");
        assertNothingStored(result);
    }
}
