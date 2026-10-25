package org.fuin.porelo.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Fails a test that leaves a vault passphrase under this JVM's kernel keyring prefix. */
public final class KeyringLeakGuard implements BeforeEachCallback, AfterEachCallback {

    private static final String VAULT_PREFIX = vaultPrefix();

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(KeyringLeakGuard.class);

    private final Path keys;
    private final BooleanSupplier available;
    private final Function<String, KernelKeyring.Forgotten> forget;

    private static String vaultPrefix() {
        final String prefix =
                (String)
                        System.getProperties()
                                .computeIfAbsent(
                                        "porelo.keyring.prefix",
                                        ignored -> "porelo-test-" + ProcessHandle.current().pid());
        if (!prefix.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException(
                    "porelo.keyring.prefix must match [a-z0-9-]{1,64} for test isolation");
        }
        return prefix + ":vault:";
    }

    /** Creates the automatically discovered guard for the real kernel keyring. */
    public KeyringLeakGuard() {
        this(
                Path.of("/proc/keys"),
                KernelKeyring::available,
                description -> new KernelKeyring(description).forget());
    }

    KeyringLeakGuard(
            Path keys,
            BooleanSupplier available,
            Function<String, KernelKeyring.Forgotten> forget) {
        this.keys = keys;
        this.available = available;
        this.forget = forget;
    }

    /** Records this JVM's vault keys before the test. */
    @Override
    public void beforeEach(ExtensionContext context) {
        if (available.getAsBoolean()) {
            descriptions().ifPresent(before -> context.getStore(NAMESPACE).put(this, before));
        }
    }

    /** Forgets and reports every vault key the test left behind. */
    @Override
    public void afterEach(ExtensionContext context) {
        final Object before = context.getStore(NAMESPACE).remove(this);
        if (!(before instanceof Set<?> earlier) || !available.getAsBoolean()) {
            return;
        }
        final Optional<Set<String>> after = descriptions();
        if (after.isEmpty()) {
            return;
        }
        final Set<String> leaked = new TreeSet<>(after.orElseThrow());
        leaked.removeAll(earlier);
        final StringBuilder failures = new StringBuilder();
        for (final String description : leaked) {
            // Unlinked keys remain in /proc/keys until the kernel's garbage collector runs.
            if (forget.apply(description) == KernelKeyring.Forgotten.NOTHING_CACHED) {
                continue;
            }
            if (!failures.isEmpty()) {
                failures.append('\n');
            }
            failures.append("left the kernel keyring key ").append(description);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.toString());
        }
    }

    private Optional<Set<String>> descriptions() {
        final Set<String> descriptions = new TreeSet<>();
        try {
            for (final String line : Files.readAllLines(keys)) {
                final String[] fields = line.strip().split("\\s+", 9);
                if (fields.length == 9
                        && fields[7].equals("user")
                        && fields[8].startsWith(VAULT_PREFIX)) {
                    final int end = fields[8].lastIndexOf(": ");
                    if (end >= VAULT_PREFIX.length()) {
                        descriptions.add(fields[8].substring(0, end));
                    }
                }
            }
            return Optional.of(descriptions);
        } catch (IOException | SecurityException ex) {
            return Optional.empty();
        }
    }
}
