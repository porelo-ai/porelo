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
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.vault.CredentialSeal;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;

/**
 * A vault entry carried from this machine's daemon to a host's: the host makes a key for one copy,
 * this machine seals the entry to it, the host opens and stores it. What carries the blob between
 * the two daemons - the window - holds ciphertext only.
 */
final class CredentialCarrying {

    /** How long a receiving key lives unused. */
    static final Duration LIFE = Duration.ofMinutes(5);

    /** How many receiving keys live at once; a newer one ends the oldest. */
    static final int MOST = 4;

    private CredentialCarrying() {}

    /**
     * Seals one vault entry to a receiving key.
     *
     * @param context This machine.
     * @param parameters {@code key}, {@code name}.
     * @return {@code outcome}, {@code sealed}, {@code name}, {@code kind}, {@code detail}; never
     *     the value.
     */
    static Map<String, Object> seal(
            final PoreloContext context, final Map<String, Object> parameters) {
        final String key = text(parameters, "key");
        final String name = text(parameters, "name");
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
