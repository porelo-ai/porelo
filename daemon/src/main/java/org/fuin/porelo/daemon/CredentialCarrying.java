package org.fuin.porelo.daemon;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.app.CredentialImport;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.vault.CredentialSeal;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * A vault entry carried from this machine's daemon to a host's: the host makes a key for one copy,
 * this machine seals the entry to it, the host opens and stores it. What carries the blob between
 * the two daemons - the window - holds ciphertext only. The entry is one of this machine's vault,
 * or an agent's sign-in here, read as an import reads it.
 */
final class CredentialCarrying {

    /** How long a receiving key lives unused. */
    static final Duration LIFE = Duration.ofMinutes(5);

    /** How many receiving keys live at once; a newer one ends the oldest. */
    static final int MOST = 4;

    /** What a sign-in that could not be read answers, never the words it failed with. */
    private static final String FAILED_READING =
            "the agent's sign-in could not be read; 'porelo vault import' at this machine says why";

    private CredentialCarrying() {}

    /**
     * Seals one vault entry, or an agent's sign-in on this machine, to a receiving key.
     *
     * <p>By {@code name} the entry is read from this machine's vault. By {@code agent} or {@code
     * provider} the sign-in is read from the agent's own file as {@code ImportCredential} reads it,
     * and sealed under the name and kind that import would store it under: this machine's vault is
     * neither opened nor written, so a shut one does not matter.
     *
     * @param context This machine.
     * @param parameters {@code key}, and {@code name} or {@code agent}, {@code provider} and {@code
     *     configDirectory}.
     * @return {@code outcome}, {@code sealed}, {@code name}, {@code kind}, {@code detail}; never
     *     the value.
     */
    static Map<String, Object> seal(
            final PoreloContext context, final Map<String, Object> parameters) {
        final String key = text(parameters, "key");
        final String name = text(parameters, "name");
        final @Nullable String agent = given(parameters, "agent");
        // Present, blank included, as ImportCredential takes it: a blank provider is a sign-in
        // asked for, and refused there, never a vault entry's seal.
        if (parameters.get("agent") instanceof String
                || parameters.get("provider") instanceof String) {
            if (!name.isEmpty()) {
                return sealed(
                        "FAILED",
                        "",
                        name,
                        "",
                        "give either the name of a vault entry or an agent's sign-in, not both");
            }
            // The provider as given, blank included, as ImportCredential takes it: a blank one is
            // refused there, where read as none it would seal whatever the agent answers.
            return signIn(
                    key,
                    context,
                    agent,
                    parameters.get("provider") instanceof String asked ? asked : null,
                    given(parameters, "configDirectory"));
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return sealed("VAULT_LOCKED", "", name, "", "the vault is not unlocked here");
        }
        final VaultEntry entry;
        try {
            entry = context.vault().read(opener.get()).get(name);
        } catch (VaultException ex) {
            return sealed("VAULT_LOCKED", "", name, "", "the vault does not open here");
        }
        if (entry == null) {
            return sealed("NO_SUCH_ENTRY", "", name, "", "the vault holds no entry of that name");
        }
        return sealing(key, name, entry);
    }

    /**
     * Seals an agent's sign-in, read from its own file and written nowhere here.
     *
     * @param key The receiving key.
     * @param context This machine.
     * @param agent The agent, or {@code null} for the only one installed.
     * @param provider The provider, or {@code null} for the one the agent answers.
     * @param configDirectory Where it keeps its sign-in, or {@code null} for what it declares.
     * @return The answer, as {@link #seal} gives it.
     */
    private static Map<String, Object> signIn(
            final String key,
            final PoreloContext context,
            final @Nullable String agent,
            final @Nullable String provider,
            final @Nullable String configDirectory) {
        final CredentialImport.Reading reading =
                CredentialImport.read(context, agent, configDirectory, provider);
        final CredentialImport.Result refusal = reading.refusal();
        if (refusal != null) {
            // A failure's words can be what the agent threw, which may quote its file: said
            // without them. The other refusals are this program's own words.
            return sealed(
                    refusal.outcome().name(),
                    "",
                    "",
                    "",
                    refusal.outcome() == CredentialImport.Outcome.FAILED
                            ? FAILED_READING
                            : refusal.detail());
        }
        final VaultEntry entry;
        try {
            entry = java.util.Objects.requireNonNull(reading.entry(), "a reading holds its entry");
        } catch (RuntimeException ex) {
            return sealed("FAILED", "", "", "", FAILED_READING);
        }
        if (!CredentialImport.plainKind(CredentialSeal.kindOf(entry))) {
            return sealed(
                    "FAILED", "", "", "", "the agent's sign-in names a kind that is not a name");
        }
        return sealing(key, reading.name(), entry);
    }

    private static Map<String, Object> sealing(
            final String key, final String name, final VaultEntry entry) {
        try {
            return sealed(
                    "SEALED",
                    CredentialSeal.seal(key, name, entry),
                    name,
                    CredentialSeal.kindOf(entry),
                    "");
        } catch (CredentialSeal.TooLarge ex) {
            return sealed("TOO_LARGE", "", name, "", String.valueOf(ex.getMessage()));
        } catch (CredentialSeal.Refused ex) {
            return sealed("BAD_KEY", "", name, "", String.valueOf(ex.getMessage()));
        }
    }

    private static Map<String, Object> sealed(
            final String outcome,
            final String blob,
            final String name,
            final String kind,
            final String detail) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("outcome", outcome);
        answer.put("sealed", blob);
        answer.put("name", name);
        answer.put("kind", kind);
        answer.put("detail", detail);
        return answer;
    }

    private static String text(final Map<String, Object> parameters, final String name) {
        return parameters.get(name) instanceof String given ? given : "";
    }

    /** A text parameter, {@code null} when absent or blank: blank names no agent or provider. */
    private static @Nullable String given(final Map<String, Object> parameters, final String name) {
        return parameters.get(name) instanceof String value && !value.isBlank() ? value : null;
    }

    /** The host's side: the receiving keys alive in this daemon, in memory only. */
    static final class Receiving {

        private record Live(CredentialSeal.Receiver receiver, Instant until) {}

        private final Supplier<Clock> clock;

        private final List<Live> live = new ArrayList<>();

        /**
         * Makes the host's side.
         *
         * @param clock The time, read on every call.
         */
        Receiving(final Supplier<Clock> clock) {
            this.clock = clock;
        }

        /**
         * Makes a receiving key for one copy.
         *
         * @param context The host.
         * @return {@code outcome} (READY or VAULT_LOCKED), {@code key}, {@code until}.
         */
        synchronized Map<String, Object> receiver(final PoreloContext context) {
            final Map<String, Object> answer = new LinkedHashMap<>();
            if (context.opener().isEmpty() || !context.vault().exists()) {
                answer.put("outcome", "VAULT_LOCKED");
                answer.put("key", "");
                answer.put("until", "");
                return answer;
            }
            final Instant now = clock.get().instant();
            prune(now);
            while (live.size() >= MOST) {
                live.remove(0);
            }
            final CredentialSeal.Receiver made = CredentialSeal.receiver();
            final Instant until = now.plus(LIFE).truncatedTo(ChronoUnit.SECONDS);
            live.add(new Live(made, until));
            answer.put("outcome", "READY");
            answer.put("key", made.publicKey());
            answer.put("until", until.toString());
            return answer;
        }

        /**
         * Opens a sealed entry with the receiving key it was sealed to, which ends that key, and
         * stores it.
         *
         * @param context The host.
         * @param parameters {@code sealed}, {@code name}, {@code kind}, {@code replace}.
         * @return {@code outcome}, {@code detail}; never the value.
         */
        Map<String, Object> receive(
                final PoreloContext context, final Map<String, Object> parameters) {
            final String blob = text(parameters, "sealed");
            final String name = text(parameters, "name");
            final String kind = text(parameters, "kind");
            final boolean replace = Boolean.TRUE.equals(parameters.get("replace"));
            final Optional<VaultFile.Opener> opener = context.opener();
            if (opener.isEmpty() || !context.vault().exists()) {
                return received("VAULT_LOCKED", "the vault is not unlocked here");
            }
            final VaultEntry entry;
            synchronized (this) {
                prune(clock.get().instant());
                if (live.isEmpty()) {
                    return received(
                            "NO_RECEIVER", "no receiving key is alive: it was used or ended");
                }
                VaultEntry opened = null;
                for (final Iterator<Live> each = live.iterator(); each.hasNext(); ) {
                    try {
                        opened = each.next().receiver().open(blob, name, kind);
                        each.remove();
                        break;
                    } catch (CredentialSeal.Refused ex) {
                        // Not sealed to this one; the next may open it.
                    }
                }
                if (opened == null) {
                    return received(
                            "REFUSED",
                            "the sealed credential does not open here: sealed to another key, for"
                                    + " another name or kind, or used already");
                }
                entry = opened;
            }
            final boolean[] existed = {false};
            try {
                context.vault()
                        .update(
                                opener.get(),
                                entries -> {
                                    if (entries.containsKey(name) && !replace) {
                                        existed[0] = true;
                                        return entries;
                                    }
                                    final Map<String, VaultEntry> changed =
                                            new LinkedHashMap<>(entries);
                                    changed.put(name, entry);
                                    return changed;
                                });
            } catch (VaultException ex) {
                return received("VAULT_LOCKED", "the vault could not be written here");
            }
            return existed[0]
                    ? received("EXISTS", "the vault already holds an entry of that name")
                    : received("STORED", "");
        }

        private void prune(final Instant now) {
            live.removeIf(each -> !now.isBefore(each.until()));
        }

        private static Map<String, Object> received(final String outcome, final String detail) {
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", outcome);
            answer.put("detail", detail);
            return answer;
        }
    }
}
