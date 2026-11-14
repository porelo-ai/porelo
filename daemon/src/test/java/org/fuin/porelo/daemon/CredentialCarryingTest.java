package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
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
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, home.resolve("bin")),
                        arguments -> 0)
                .asking(prompt -> PASSPHRASE.clone());
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
}
