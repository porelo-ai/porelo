package org.fuin.porelo.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

/** Prevents leaked caches from exhausting the account's key quota or erasing earlier keys. */
class KeyringLeakGuardTest {

    @TempDir Path dir;

    @Test
    void onlyNewUserKeysForVaultsAreForgottenAndEveryLeakIsNamed() throws IOException {
        final Path keys = dir.resolve("keys");
        final String earlier = line("user", vaultPrefix() + "earlier");
        Files.writeString(keys, earlier);
        final List<String> forgotten = new ArrayList<>();
        final KeyringLeakGuard guard =
                new KeyringLeakGuard(keys, () -> true, forgetting(forgotten));
        final ExtensionContext context = context();
        guard.beforeEach(context);
        Files.writeString(
                keys,
                earlier
                        + line("user", vaultPrefix() + "first")
                        + line("user", vaultPrefix() + "second")
                        + line("keyring", vaultPrefix() + "not-a-user-key")
                        + line("user", "another-application")
                        + line("user", "porelo:vault:real-account")
                        + line("user", "porelo-test-other-jvm:vault:another-test")
                        + line("user", "other:" + vaultPrefix() + "not-a-prefix"));

        assertThatThrownBy(() -> guard.afterEach(context))
                .as("a test that leaks fails and names each cache it left")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("left the kernel keyring key " + vaultPrefix() + "first")
                .hasMessageContaining("left the kernel keyring key " + vaultPrefix() + "second");
        assertThat(forgotten)
                .as("the exact reference set excludes earlier keys, other types and other prefixes")
                .containsExactlyInAnyOrder(vaultPrefix() + "first", vaultPrefix() + "second");
    }

    @Test
    void aTestThatForgetsItsKeyPassesAndKeepsEarlierKeys() throws IOException {
        final Path keys = dir.resolve("keys");
        final String earlier = line("user", vaultPrefix() + "earlier");
        Files.writeString(keys, earlier);
        final List<String> forgotten = new ArrayList<>();
        final KeyringLeakGuard guard =
                new KeyringLeakGuard(keys, () -> true, forgetting(forgotten));
        final ExtensionContext context = context();
        guard.beforeEach(context);
        Files.writeString(keys, earlier + line("user", vaultPrefix() + "temporary"));
        Files.writeString(keys, earlier);

        assertThatCode(() -> guard.afterEach(context))
                .as("a cache already forgotten is not a leak")
                .doesNotThrowAnyException();
        assertThat(forgotten).as("the earlier cache belongs to the account").isEmpty();
    }

    @Test
    void anUnavailableKeyringLeavesKeysAlone() throws IOException {
        final Path keys = dir.resolve("keys");
        Files.writeString(keys, "");
        final List<String> forgotten = new ArrayList<>();
        final KeyringLeakGuard guard =
                new KeyringLeakGuard(keys, () -> false, forgetting(forgotten));
        final ExtensionContext context = context();
        guard.beforeEach(context);
        Files.writeString(keys, line("user", vaultPrefix() + "unavailable"));

        assertThatCode(() -> guard.afterEach(context))
                .as("an unavailable keyring disables the guard")
                .doesNotThrowAnyException();
        assertThat(forgotten).as("no cleanup is attempted without a keyring").isEmpty();
    }

    @Test
    void anUnreadableReferenceSetNeverMakesEarlierKeysLookLikeLeaks() throws IOException {
        final Path keys = dir.resolve("missing");
        final List<String> forgotten = new ArrayList<>();
        final KeyringLeakGuard guard =
                new KeyringLeakGuard(keys, () -> true, forgetting(forgotten));
        final ExtensionContext context = context();
        guard.beforeEach(context);
        Files.writeString(keys, line("user", vaultPrefix() + "earlier"));

        assertThatCode(() -> guard.afterEach(context))
                .as("without a reference set no key can be attributed to this test")
                .doesNotThrowAnyException();
        assertThat(forgotten)
                .as("an unreadable reference set does not erase account keys")
                .isEmpty();
    }

    @Test
    void anUnreadableKeyListingAfterTheTestDisablesTheGuard() throws IOException {
        final Path keys = dir.resolve("keys");
        Files.writeString(keys, "");
        final List<String> forgotten = new ArrayList<>();
        final KeyringLeakGuard guard =
                new KeyringLeakGuard(keys, () -> true, forgetting(forgotten));
        final ExtensionContext context = context();
        guard.beforeEach(context);
        Files.delete(keys);

        assertThatCode(() -> guard.afterEach(context))
                .as("an unreadable listing is allowed on machines without proc access")
                .doesNotThrowAnyException();
        assertThat(forgotten).as("no keys can be identified for cleanup").isEmpty();
    }

    @Test
    void anotherProcessesRealVaultKeyIsNeitherForgottenNorReported() {
        requireKeyring();
        final KernelKeyring other = new KernelKeyring("porelo:vault:" + UUID.randomUUID());
        final KeyringLeakGuard guard = new KeyringLeakGuard();
        final ExtensionContext context = context();
        guard.beforeEach(context);
        try {
            other.store("another process fixture".toCharArray());
            assertThatCode(() -> guard.afterEach(context))
                    .as("a concurrent unlock of another process's vault is not this test's leak")
                    .doesNotThrowAnyException();
            assertThat(other.read())
                    .as("the guard preserves the other process's cached passphrase")
                    .hasValueSatisfying(
                            value ->
                                    assertThat(value)
                                            .as("the original passphrase remains unchanged")
                                            .containsExactly(
                                                    "another process fixture".toCharArray()));
        } finally {
            other.forget();
        }
    }

    @Test
    void aRealLeakedKeyFailsTheTestAndIsNoLongerCached() throws IOException {
        requireKeyring();
        final String description = vaultPrefix() + UUID.randomUUID();
        final KernelKeyring keyring = new KernelKeyring(description);
        final KeyringLeakGuard guard = new KeyringLeakGuard();
        final ExtensionContext context = context();
        guard.beforeEach(context);
        try {
            keyring.store("guard fixture".toCharArray());
            assertThat(Files.readAllLines(Path.of("/proc/keys")))
                    .as("the real kernel exposes the stored key to the guard")
                    .anyMatch(line -> line.contains(" " + description + ": "));

            assertThatThrownBy(() -> guard.afterEach(context))
                    .as("a real leaked passphrase must fail the test")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("left the kernel keyring key " + description);
            assertThat(keyring.read()).as("the guard forgot the leaked passphrase").isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aRealKeyForgottenByTheTestPasses() {
        requireKeyring();
        final KernelKeyring keyring = new KernelKeyring(vaultPrefix() + UUID.randomUUID());
        final KeyringLeakGuard guard = new KeyringLeakGuard();
        final ExtensionContext context = context();
        guard.beforeEach(context);
        try {
            keyring.store("guard fixture".toCharArray());
            keyring.forget();

            assertThatCode(() -> guard.afterEach(context))
                    .as("a test that forgets its real key leaves no leak")
                    .doesNotThrowAnyException();
            assertThat(keyring.read()).as("the passphrase is no longer cached").isEmpty();
        } finally {
            keyring.forget();
        }
    }

    private static void requireKeyring() {
        assumeTrue(KernelKeyring.available(), KernelKeyring.unavailable());
        assumeTrue(Files.isReadable(Path.of("/proc/keys")), "/proc/keys is unreadable");
    }

    private static String vaultPrefix() {
        return System.getProperty(
                        "porelo.keyring.prefix", "porelo-test-" + ProcessHandle.current().pid())
                + ":vault:";
    }

    private static String line(String type, String description) {
        return "1234abcd I--Q--- 1 perm 3f2f0000 1000 1000 " + type + " " + description + ": 6\n";
    }

    private static Function<String, KernelKeyring.Forgotten> forgetting(List<String> forgotten) {
        return description -> {
            forgotten.add(description);
            return KernelKeyring.Forgotten.CLEARED;
        };
    }

    private static ExtensionContext context() {
        final Map<Object, Object> values = new HashMap<>();
        final ExtensionContext.Store store =
                (ExtensionContext.Store)
                        Proxy.newProxyInstance(
                                ExtensionContext.Store.class.getClassLoader(),
                                new Class<?>[] {ExtensionContext.Store.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "put" -> values.put(args[0], args[1]);
                                            case "get" -> values.get(args[0]);
                                            case "remove" -> values.remove(args[0]);
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            method.getName());
                                        });
        return (ExtensionContext)
                Proxy.newProxyInstance(
                        ExtensionContext.class.getClassLoader(),
                        new Class<?>[] {ExtensionContext.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getStore")) {
                                return store;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
