package org.fuin.porelo.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.StdinPassphrase;
import org.fuin.porelo.vault.StoredKey;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The person's step at a host's stop: initializes or unlocks this account's vault and stores any
 * keys handed in, either interactively at a terminal or from base64 lines on standard input with
 * {@code --stdin}.
 *
 * <p><strong>No secret ever reaches a command line, log, output stream or exception
 * message.</strong> With {@code --stdin}, every line is checked before any secret is used; without
 * {@code --stdin} and without a terminal, the command refuses at once with exit code 69.
 */
@Command(
        name = "stop-secrets",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Initializes or unlocks this account's vault and stores keys at a host's stop.")
public class HostStopSecretsCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    private static final int MAXIMUM_LINES = 256;

    private static final int MAXIMUM_INPUT_BYTES = 1024 * 1024;

    private static final Set<String> RETIRED_NEEDS =
            Set.of("wireguard", "forge-certificate", "identities", "drive");

    private static final Set<String> STDIN_NEEDS = Set.of("vault-new", "vault", "keys");

    private static final Set<String> TERMINAL_NEEDS = Set.of("vault-new", "vault");

    private static final Pattern VALID_BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

    private static final Pattern KEY_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    @Option(
            names = "--stdin",
            description = "Reads one base64-encoded secret line per need from a pipe.")
    private boolean stdin;

    @Parameters(
            paramLabel = "<need>",
            description = "What this stop needs: vault-new, vault, or (with --stdin) keys.")
    private @Nullable List<String> needs;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    /** Whether an interactive terminal is present; another check only in a test. */
    BooleanSupplier terminal = Offer::atTerminal;

    /** Reads the answer to {@code Try again? [Y/n]}; another reader only in a test. */
    @Nullable Supplier<@Nullable String> retryReader;

    /** Caches only a verified passphrase; replaceable where the test has no kernel keyring. */
    java.util.function.Predicate<char[]> cache = this::cachePassphrase;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (needs == null || needs.isEmpty()) {
            err.println(
                    "porelo: usage: porelo host stop-secrets [--stdin] [vault-new|vault] [keys]");
            err.flush();
            return 64;
        }
        final Set<String> seenNeeds = new LinkedHashSet<>();
        for (final String need : needs) {
            if (RETIRED_NEEDS.contains(need)) {
                err.println("porelo: " + need + " is no need of the host any more");
                err.flush();
                return 64;
            }
            final Set<String> allowed = stdin ? STDIN_NEEDS : TERMINAL_NEEDS;
            if (!allowed.contains(need)) {
                err.println(
                        "porelo: usage: porelo host stop-secrets [--stdin] [vault-new|vault]"
                                + " [keys]");
                err.flush();
                return 64;
            }
            if (!seenNeeds.add(need)) {
                err.println("porelo: duplicate stop need: " + need);
                err.flush();
                return 64;
            }
        }
        if (seenNeeds.contains("vault-new") && seenNeeds.contains("vault")) {
            err.println("porelo: vault-new and vault exclude each other");
            err.flush();
            return 64;
        }
        if (stdin) {
            if (context.input().terminal()) {
                err.println("porelo: --stdin reads its lines from a pipe, not a terminal");
                err.flush();
                return 64;
            }
            return runStdin(seenNeeds, out, err);
        }
        if (!terminal.getAsBoolean()) {
            err.println("porelo: the stop must run in an interactive terminal");
            err.flush();
            return 69;
        }
        return runInteractive(new ArrayList<>(seenNeeds), out, err);
    }

    private int runStdin(
            final Set<String> seenNeeds, final PrintWriter out, final PrintWriter err) {
        byte @Nullable [] vaultSecret = null;
        final Map<String, byte[]> keySecrets = new LinkedHashMap<>();
        char @Nullable [] unlockedPassphrase = null;
        try {
            final List<String> lines = readLines();
            if (lines == null) {
                err.println(
                        "porelo: stop input could not be read; use at most 256 lines and 1 MiB of UTF-8");
                err.flush();
                return 64;
            }
            for (final String line : lines) {
                if (line.startsWith("vault-new ")) {
                    if (!seenNeeds.contains("vault-new")) {
                        err.println("porelo: vault-new was not named for this stop");
                        err.flush();
                        return 64;
                    }
                    if (vaultSecret != null) {
                        err.println("porelo: vault-new given a second time");
                        err.flush();
                        return 64;
                    }
                    final byte[] decoded =
                            decodeBase64Secret(line.substring("vault-new ".length()));
                    if (decoded == null) {
                        err.println("porelo: vault-new is not valid base64");
                        err.flush();
                        return 64;
                    }
                    vaultSecret = decoded;
                } else if (line.startsWith("vault ")) {
                    if (!seenNeeds.contains("vault")) {
                        err.println("porelo: vault was not named for this stop");
                        err.flush();
                        return 64;
                    }
                    if (vaultSecret != null) {
                        err.println("porelo: vault given a second time");
                        err.flush();
                        return 64;
                    }
                    final byte[] decoded = decodeBase64Secret(line.substring("vault ".length()));
                    if (decoded == null) {
                        err.println("porelo: vault is not valid base64");
                        err.flush();
                        return 64;
                    }
                    vaultSecret = decoded;
                } else if (line.startsWith("key ")) {
                    if (!seenNeeds.contains("keys")) {
                        err.println("porelo: keys was not named for this stop");
                        err.flush();
                        return 64;
                    }
                    final String rest = line.substring("key ".length());
                    final int space = rest.indexOf(' ');
                    if (space <= 0) {
                        err.println("porelo: key line names no value");
                        err.flush();
                        return 64;
                    }
                    final String keyName = rest.substring(0, space);
                    final String keyBase64 = rest.substring(space + 1);
                    if (!KEY_NAME.matcher(keyName).matches()) {
                        err.println("porelo: invalid key name");
                        err.flush();
                        return 64;
                    }
                    if (keySecrets.containsKey(keyName)) {
                        err.println("porelo: key given a second time: " + keyName);
                        err.flush();
                        return 64;
                    }
                    final byte[] decoded = decodeBase64Secret(keyBase64);
                    if (decoded == null) {
                        err.println("porelo: key " + keyName + " is not valid base64");
                        err.flush();
                        return 64;
                    }
                    keySecrets.put(keyName, decoded);
                } else {
                    err.println("porelo: unexpected stop line");
                    err.flush();
                    return 64;
                }
            }

            if (seenNeeds.contains("vault-new") && vaultSecret == null) {
                err.println("porelo: missing vault-new line");
                err.flush();
                return 64;
            }
            if (seenNeeds.contains("vault") && vaultSecret == null) {
                err.println("porelo: missing vault line");
                err.flush();
                return 64;
            }
            if (seenNeeds.contains("keys")
                    && keySecrets.isEmpty()
                    && !seenNeeds.contains("vault-new")) {
                err.println("porelo: missing key line");
                err.flush();
                return 64;
            }

            // Validate decoded keys before creating or unlocking anything.
            final Map<String, VaultEntry> prepared = new LinkedHashMap<>();
            for (final Map.Entry<String, byte[]> entry : keySecrets.entrySet()) {
                try {
                    final String raw = decodeUtf8(entry.getValue());
                    if (raw == null || TaskSecrets.reserved(entry.getKey())) {
                        throw new VaultException("the key cannot be stored");
                    }
                    final String value =
                            VaultPutCommand.readValue(
                                    false,
                                    () -> new char[0],
                                    new BufferedReader(new java.io.StringReader(raw)));
                    if (value.isEmpty()) {
                        throw new VaultException("the key has no value");
                    }
                    prepared.put(entry.getKey(), toVaultEntry(entry.getKey(), value));
                } catch (final IOException | VaultException | IllegalArgumentException ex) {
                    err.println("porelo: vault put refused a key; check its value and retry");
                    err.flush();
                    return 65;
                }
            }

            if (seenNeeds.contains("vault-new") && vaultSecret != null) {
                unlockedPassphrase = toPassphraseChars(vaultSecret);
                if (unlockedPassphrase == null
                        || unlockedPassphrase.length == 0
                        || context.vault().exists()) {
                    err.println("porelo: vault init refused the passphrase");
                    err.flush();
                    return 65;
                }
                try {
                    initAndCacheVault(unlockedPassphrase, err);
                } catch (final VaultException | IllegalStateException ex) {
                    err.println("porelo: vault init refused the passphrase");
                    err.flush();
                    return 65;
                }
            }

            if (seenNeeds.contains("vault") && vaultSecret != null) {
                unlockedPassphrase = toPassphraseChars(vaultSecret);
                if (unlockedPassphrase == null
                        || unlockedPassphrase.length == 0
                        || !unlockAndCacheVault(unlockedPassphrase)) {
                    err.println("porelo: vault unlock refused the passphrase");
                    err.flush();
                    return 65;
                }
            }

            if (!prepared.isEmpty()) {
                try {
                    final Optional<VaultFile.Opener> opener =
                            unlockedPassphrase != null
                                    ? Optional.of(VaultFile.Opener.passphrase(unlockedPassphrase))
                                    : context.opener();
                    if (opener.isEmpty()) {
                        err.println("porelo: the vault is shut; run 'porelo vault unlock' first");
                        err.flush();
                        return 65;
                    }
                    context.vault()
                            .update(
                                    opener.get(),
                                    entries -> {
                                        prepared.forEach(
                                                (name, value) ->
                                                        VaultPutCommand.replace(
                                                                entries, name, value));
                                        return entries;
                                    });
                } catch (final VaultException | IllegalArgumentException ex) {
                    err.println("porelo: vault put refused the keys; unlock the vault and retry");
                    err.flush();
                    return 65;
                }
            }

            out.println("Your stop is done.");
            out.flush();
            return 0;
        } catch (final VaultException | IllegalStateException ex) {
            err.println("porelo: the vault refused this stop; check the vault and retry");
            err.flush();
            return 65;
        } finally {
            if (vaultSecret != null) {
                Arrays.fill(vaultSecret, (byte) 0);
            }
            if (unlockedPassphrase != null) {
                Arrays.fill(unlockedPassphrase, '\0');
            }
            for (final byte[] secret : keySecrets.values()) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }

    private int runInteractive(
            final List<String> orderedNeeds, final PrintWriter out, final PrintWriter err) {
        out.println("Porelo host stop");
        out.println("Account: " + System.getProperty("user.name", ""));
        out.println("Needs: " + String.join(" ", orderedNeeds));
        out.println();
        out.println("Each passphrase stays on this host; nothing is echoed.");
        out.flush();

        for (final String need : orderedNeeds) {
            while (true) {
                out.println();
                final boolean done;
                if ("vault-new".equals(need)) {
                    out.println("Create this account's Porelo vault.");
                    out.flush();
                    done = promptVaultNew(err);
                } else {
                    out.println("Unlock this account's Porelo vault.");
                    out.flush();
                    done = promptVaultUnlock(err);
                }
                if (done) {
                    break;
                }
                if (!retryOrCancel(err)) {
                    err.println(
                            "Cancelled; the host waits at this stop. Running up again comes back"
                                    + " here.");
                    err.flush();
                    return 75;
                }
            }
        }

        out.println();
        out.println("The host's stop is done; close this terminal, and setup goes on.");
        out.flush();
        return 0;
    }

    private boolean promptVaultNew(final PrintWriter err) {
        if (context.vault().exists()) {
            err.println("porelo: vault already exists at " + context.vault().path());
            err.flush();
            return false;
        }
        final char[] first = context.prompt().ask("Enter new vault passphrase: ");
        if (first == null || first.length == 0) {
            err.println("porelo: passphrase must not be empty");
            err.flush();
            return false;
        }
        char @Nullable [] second = null;
        try {
            second = context.prompt().ask("Confirm vault passphrase: ");
            if (second == null || !Arrays.equals(first, second)) {
                err.println("porelo: passphrases do not match");
                err.flush();
                return false;
            }
            initAndCacheVault(first, err);
            return true;
        } catch (final VaultException | IllegalStateException ex) {
            err.println("porelo: vault init refused the passphrase");
            err.flush();
            return false;
        } finally {
            Arrays.fill(first, '\0');
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    private boolean promptVaultUnlock(final PrintWriter err) {
        final char[] passphrase = context.prompt().ask("Vault passphrase: ");
        if (passphrase == null || passphrase.length == 0) {
            err.println("porelo: passphrase must not be empty");
            err.flush();
            return false;
        }
        try {
            if (!unlockAndCacheVault(passphrase)) {
                err.println("porelo: vault unlock refused the passphrase");
                err.flush();
                return false;
            }
            return true;
        } catch (final VaultException | IllegalStateException ex) {
            err.println("porelo: vault unlock refused the passphrase");
            err.flush();
            return false;
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private void initAndCacheVault(final char[] passphrase, final PrintWriter err) {
        context.vault().write(Map.of(), passphrase);
        if (!cache.test(passphrase)) {
            err.println(
                    "porelo: the vault was created; its passphrase could not be cached; run 'porelo vault unlock' before the next command");
            err.flush();
        }
    }

    private boolean unlockAndCacheVault(final char[] passphrase) {
        final VaultFile vault = context.vault();
        if (!vault.exists() || !vault.hasPassphrase() || !vault.accepts(passphrase)) {
            return false;
        }
        return cache.test(passphrase);
    }

    private boolean cachePassphrase(final char[] passphrase) {
        if (!KernelKeyring.available()) {
            return false;
        }
        try {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).store(passphrase, null);
            return true;
        } catch (final VaultException ex) {
            return false;
        }
    }

    private VaultEntry toVaultEntry(final String keyName, final String rawSecret) {
        if (OpenSshPrivateKey.looksLikeAnyPrivateKey(rawSecret)) {
            final PrintWriter silent = new PrintWriter(java.io.Writer.nullWriter());
            final String converted;
            try {
                converted = VaultPutCommand.converted(context, rawSecret, silent, silent);
            } catch (final org.fuin.porelo.core.process.CommandException ex) {
                throw new VaultException("the key could not be converted");
            }
            if (converted == null) {
                throw new VaultException("the key could not be converted");
            }
            final String stored = StoredKey.toStore(converted);
            StoredKey.of(stored, keyName);
            return new VaultEntry(stored, "ssh-key", Map.of());
        }
        return VaultEntry.of(rawSecret.strip());
    }

    private static @Nullable String decodeUtf8(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException ex) {
            return null;
        }
    }

    private boolean retryOrCancel(final PrintWriter err) {
        err.print("Try again? [Y/n] ");
        err.flush();
        final String answer;
        if (retryReader != null) {
            answer = retryReader.get();
        } else {
            try {
                final BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        context.input().stream(), StandardCharsets.UTF_8));
                answer = reader.readLine();
            } catch (final IOException ex) {
                return false;
            }
        }
        if (answer == null) {
            return false;
        }
        final String trimmed = answer.strip();
        return !"n".equals(trimmed) && !"N".equals(trimmed);
    }

    private @Nullable List<String> readLines() {
        final List<String> lines = new ArrayList<>();
        try {
            final byte[] bytes = context.input().stream().readNBytes(MAXIMUM_INPUT_BYTES + 1);
            if (bytes.length > MAXIMUM_INPUT_BYTES) {
                Arrays.fill(bytes, (byte) 0);
                return null;
            }
            final String text;
            try {
                text = decodeUtf8(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            if (text == null) {
                return null;
            }
            final BufferedReader reader = new BufferedReader(new java.io.StringReader(text));
            String raw;
            while ((raw = reader.readLine()) != null) {
                if (lines.size() >= MAXIMUM_LINES) {
                    return null;
                }
                final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                lines.add(line);
            }
            return lines;
        } catch (final IOException ex) {
            return null;
        }
    }

    private static byte @Nullable [] decodeBase64Secret(final String value) {
        if (value.isEmpty() || value.length() % 4 != 0 || !VALID_BASE64.matcher(value).matches()) {
            return null;
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
        return decoded.length == 0 ? null : decoded;
    }

    private static char @Nullable [] toPassphraseChars(final byte[] raw) {
        try {
            return new StdinPassphrase(new java.io.ByteArrayInputStream(raw))
                    .passphrase()
                    .orElse(null);
        } catch (final VaultException ex) {
            return null;
        }
    }
}
