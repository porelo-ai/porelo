package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;

/**
 * Copies a credential an agent already holds on this node into the vault.
 *
 * <p><strong>Nothing secret moves anywhere.</strong> The value is read from a file on the node's
 * own disk and written to the node's own vault. That is what makes this usable from an interface
 * while storing one by hand is not: the caller names an agent, and nothing else crosses.
 *
 * <p><strong>One implementation, two callers.</strong> The CLI and the daemon differ in exactly one
 * thing - where a passphrase comes from - so that is a parameter and the rest is shared. Two
 * implementations of "which key does this go under" would eventually disagree, and the way they
 * would disagree is by storing a credential where nothing looks for it, which reads as a missing
 * credential rather than as a bug.
 */
public final class CredentialImport {

    /** What happened, or why nothing did. */
    public enum Outcome {

        /** Copied into the vault. */
        IMPORTED,

        /** No agent of that name, or none given and more than one installed. */
        NO_SUCH_AGENT,

        /** The agent does not say where it keeps its credentials. */
        NO_CONFIG_DIRECTORY,

        /** The agent is installed but has not been logged in on this node. */
        NOTHING_TO_IMPORT,

        /** The vault cannot be opened without a passphrase nobody can be asked for here. */
        VAULT_LOCKED,

        /** The agent's answer is for another provider, or says none when one was asked for. */
        WRONG_PROVIDER,

        /** The vault could not be written. */
        FAILED
    }

    /**
     * What an import did.
     *
     * @param outcome What happened.
     * @param name The vault key it was stored under, or "" when nothing was stored.
     * @param type The kind of credential, or "".
     * @param length How many characters arrived, so a caller can show that something did without
     *     showing what. Zero when nothing was stored.
     * @param source Where it was read from, or "".
     * @param detail Why it failed, or a warning about what was stored. "" when there is nothing to
     *     say.
     */
    public record Result(
            Outcome outcome, String name, String type, int length, String source, String detail) {

        /** Without the length, which is for the credential's owner and not for a line. */
        @Override
        public String toString() {
            return "Result[" + outcome + ", " + name + ", " + type + ", " + source + "]";
        }
    }

    private CredentialImport() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Result failed(Outcome outcome, String detail) {
        return new Result(outcome, "", "", 0, "", detail);
    }

    /**
     * What an agent's sign-in on this machine would be stored as, read from the agent's own file
     * and never written anywhere.
     *
     * <p>{@link #run} stores it in this machine's vault; a daemon carrying it to a host seals
     * {@link #entry()} under {@link #name()} instead, so the host keeps it where an import here
     * would.
     */
    public static final class Reading {

        private final @Nullable Result refusal;

        private final String name;

        private final @Nullable Credential value;

        private final @Nullable SelectedProvider selection;

        private final boolean byDefault;

        private final String source;

        private Reading(
                final @Nullable Result refusal,
                final String name,
                final @Nullable Credential value,
                final @Nullable SelectedProvider selection,
                final boolean byDefault,
                final String source) {
            this.refusal = refusal;
            this.name = name;
            this.value = value;
            this.selection = selection;
            this.byDefault = byDefault;
            this.source = source;
        }

        private static Reading refused(final Outcome outcome, final String detail) {
            return new Reading(failed(outcome, detail), "", null, null, false, "");
        }

        /**
         * Returns why nothing was read.
         *
         * @return The refusal, as {@link #run} answers it, or {@code null} when the sign-in was
         *     read.
         */
        public @Nullable Result refusal() {
            return refusal;
        }

        /**
         * Returns the vault key the sign-in is stored under.
         *
         * @return The name, or "" when nothing was read.
         */
        public String name() {
            return name;
        }

        /**
         * Returns the entry as an import stores it under {@link #name()}: the value, its kind and
         * the settings kept beside it.
         *
         * <p>Made when asked, not when read: an import decides a shut vault and its guard first, as
         * it always did, and only then makes the entry, which can still refuse what the agent read.
         *
         * @return The entry, or {@code null} when nothing was read.
         * @throws IllegalArgumentException When the sign-in cannot be an entry.
         */
        public @Nullable VaultEntry entry() {
            if (value == null) {
                return null;
            }
            // Exactly as the import puts it, read back from a scratch map: one place says what a
            // stored sign-in holds.
            final Map<String, VaultEntry> scratch = new LinkedHashMap<>();
            LoginCredentials.put(scratch, name, value, false, LoginCredentials.kept(selection));
            return Objects.requireNonNull(scratch.get(name), "LoginCredentials.put stores the key");
        }

        /** What to say beside a stored sign-in, made after it is stored, as the import did. */
        private String note() {
            final Credential read = Objects.requireNonNull(value, "a reading holds its value");
            // The same check 'vault put' makes. An agent's own file is the likelier source of a
            // real credential, but a half-written or logged-out one still reads as a success.
            final StringBuilder note = new StringBuilder();
            final String suspicious = new VaultEntry(read.secret(), read.type()).suspicious();
            if (suspicious != null) {
                note.append("check what was imported - ").append(suspicious);
            }
            final String unbrokerable =
                    selection == null ? null : selection.route().unbrokerableReason(read.type());
            if (unbrokerable != null) {
                note.append(note.isEmpty() ? "" : "; ")
                        .append("stored, but a task will refuse it: ")
                        .append(unbrokerable);
            }
            return note.toString();
        }

        /**
         * Returns where the sign-in was read from.
         *
         * @return The file's directory, or "" when nothing was read.
         */
        public String source() {
            return source;
        }

        /** Names what it is, never what it holds. */
        @Override
        public String toString() {
            return "Reading[" + (refusal == null ? name : refusal.outcome()) + "]";
        }
    }

    /**
     * Reads an agent's sign-in as an import would store it, writing no vault.
     *
     * @param context The machine.
     * @param agentName Which agent, or {@code null} for the only one installed.
     * @param configDirectory Where it keeps its credentials, or {@code null} for what it declares.
     * @param provider The provider asked for, or {@code null} for whichever the agent answers.
     * @return What would be stored, or why nothing would; never {@link Outcome#VAULT_LOCKED}, as no
     *     vault is opened.
     */
    public static Reading read(
            final PoreloContext context,
            final @Nullable String agentName,
            final @Nullable String configDirectory,
            final @Nullable String provider) {
        try (var agents = context.agents()) {
            return read(context, agents, agentName, configDirectory, provider);
        } catch (RuntimeException ex) {
            return Reading.refused(Outcome.FAILED, CliErrors.reason(ex));
        }
    }

    private static Reading read(
            final PoreloContext context,
            final org.fuin.porelo.agent.api.InstalledAgents agents,
            final @Nullable String agentName,
            final @Nullable String configDirectory,
            final @Nullable String provider) {

        final Optional<InstalledAgent> found =
                agentName != null
                        ? agents.find(agentName)
                        : agents.names().size() == 1
                                ? agents.find(agents.names().getFirst())
                                : Optional.empty();
        if (found.isEmpty()) {
            return Reading.refused(
                    Outcome.NO_SUCH_AGENT,
                    "no such agent; installed: " + String.join(", ", agents.names()));
        }
        final InstalledAgent agent = found.get();

        final String declared =
                configDirectory != null ? configDirectory : agent.definition().configDirectory();
        if (declared == null) {
            return Reading.refused(
                    Outcome.NO_CONFIG_DIRECTORY,
                    "'" + agent.name() + "' does not say" + " where it keeps its credentials");
        }

        final Path directory = VaultImportCommand.expand(declared);
        if (provider != null) {
            final SelectedProvider.Choice asked =
                    SelectedProvider.consider(context.providers(), agent.definition(), provider);
            if (asked.refusal() != null || asked.selection() == null) {
                return Reading.refused(Outcome.WRONG_PROVIDER, asked.detail());
            }
        }
        final Optional<Credential> credential;
        try {
            credential = agent.extractCredential(directory, provider);
        } catch (InstalledAgent.UnusableProvider ex) {
            return Reading.refused(Outcome.WRONG_PROVIDER, CliErrors.reason(ex));
        }
        if (credential.isEmpty()) {
            return Reading.refused(
                    Outcome.NOTHING_TO_IMPORT,
                    provider != null
                            ? "'"
                                    + agent.name()
                                    + "' holds no sign-in for '"
                                    + provider
                                    + "' that can be imported from "
                                    + directory
                            : "nothing to import from "
                                    + directory
                                    + " - log in with the agent on this node first");
        }
        final Credential value = credential.get();
        final String wrong = wrongProvider(provider, value.provider());
        if (wrong != null) {
            return Reading.refused(Outcome.WRONG_PROVIDER, wrong);
        }

        // Stored under the provider's name: the credential is the provider's, and a second
        // agent reaching the same one must find it rather than store its own copy. One the
        // agent names and this machine cannot store under is refused, not put under the
        // default.
        final SelectedProvider.Choice chosen =
                SelectedProvider.consider(
                        context.providers(), agent.definition(), value.provider());
        if (chosen.refusal() != null) {
            return Reading.refused(
                    value.provider() == null ? Outcome.FAILED : Outcome.WRONG_PROVIDER,
                    chosen.detail());
        }
        final SelectedProvider selection = chosen.selection();
        final String key = selection == null ? agent.name() : selection.name();

        return new Reading(
                null,
                key,
                value,
                selection,
                key.equals(defaultName(context, agent)),
                directory.toString());
    }

    /**
     * Lists the sign-ins the installed agents hold on this machine, by name: for each agent, each
     * provider it can drive that its own file holds a sign-in for, or the one it answers when it is
     * not brokered.
     *
     * @param context The machine.
     * @return One row per sign-in an import or a carry would take, in the agents' order; never a
     *     value.
     */
    public static List<Importable> importable(final PoreloContext context) {
        final List<Importable> rows = new ArrayList<>();
        try (var agents = context.agents()) {
            for (final String name : agents.names()) {
                final Optional<InstalledAgent> agent = agents.find(name);
                if (agent.isEmpty()) {
                    continue;
                }
                final List<String> drivable =
                        SelectedProvider.drivable(context.providers(), agent.get().definition());
                // An agent that is not brokered is asked for whatever it holds.
                final List<@Nullable String> each = new ArrayList<>();
                if (drivable.isEmpty()) {
                    each.add(null);
                }
                each.addAll(drivable);
                for (final @Nullable String provider : each) {
                    final Reading reading;
                    final @Nullable VaultEntry entry;
                    try {
                        reading = read(context, agents, name, null, provider);
                        entry = reading.entry();
                    } catch (RuntimeException ex) {
                        // One agent that cannot answer hides none of the others.
                        continue;
                    }
                    // A kind is a name; one that is not could be anything the agent wrote there.
                    if (reading.refusal() == null
                            && entry != null
                            && plainKind(org.fuin.porelo.vault.CredentialSeal.kindOf(entry))) {
                        rows.add(
                                new Importable(
                                        name,
                                        provider == null ? "" : provider,
                                        org.fuin.porelo.vault.CredentialSeal.kindOf(entry)));
                    }
                }
            }
        }
        return rows;
    }

    /**
     * Tells whether a kind is a plain name, which an answer may carry: an agent's file says the
     * kind, and one that is not a name could be the value itself.
     *
     * @param kind The kind, "" for none.
     * @return Whether it may be said.
     */
    public static boolean plainKind(final String kind) {
        return kind.isEmpty() || kind.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    /**
     * One sign-in an installed agent holds on this machine, by name only.
     *
     * @param agent The agent, as it is installed.
     * @param provider The provider it is for, "" for an agent that is not brokered.
     * @param kind Its kind, "" for none.
     */
    public record Importable(String agent, String provider, String kind) {}

    /**
     * Copies an agent's credential into the vault.
     *
     * @param context The machine.
     * @param agentName Which agent, or {@code null} for the only one installed.
     * @param configDirectory Where it keeps its credentials, or {@code null} for what it declares.
     * @param provider The provider asked for, or {@code null} for whichever the agent answers.
     * @param opener Supplies what opens the vault, or nothing when it cannot be had.
     * @return What happened.
     */
    public static Result run(
            PoreloContext context,
            @Nullable String agentName,
            @Nullable String configDirectory,
            @Nullable String provider,
            Supplier<Optional<org.fuin.porelo.vault.VaultFile.Opener>> opener) {

        final Reading reading = read(context, agentName, configDirectory, provider);
        final Result refusal = reading.refusal();
        if (refusal != null) {
            return refusal;
        }
        final String key = reading.name();
        final Credential value = Objects.requireNonNull(reading.value, "a reading holds its value");
        final SelectedProvider selection = reading.selection;

        try {
            // Whatever opens the vault, not the passphrase in particular: a machine a device
            // unlocked stores a credential exactly as one a person unlocked does.
            final Optional<org.fuin.porelo.vault.VaultFile.Opener> way = opener.get();
            if (way.isEmpty()) {
                return failed(
                        Outcome.VAULT_LOCKED,
                        "the vault is locked, and nothing here can"
                                + " ask for a passphrase - unlock it at the machine, or with a device");
            }

            final boolean[] guarded = {false};
            final boolean[] endless = {false};
            context.vault()
                    .update(
                            way.get(),
                            entries -> {
                                if (provider == null
                                        && !reading.byDefault
                                        && entries.containsKey(key)) {
                                    guarded[0] = true;
                                    return entries;
                                }
                                LoginCredentials.put(
                                        entries,
                                        key,
                                        value,
                                        false,
                                        LoginCredentials.kept(selection));
                                final VaultEntry stored = entries.get(key);
                                endless[0] =
                                        stored != null && CredentialLifetime.endless(stored, null);
                                return entries;
                            });

            if (guarded[0]) {
                return failed(
                        Outcome.WRONG_PROVIDER,
                        "this agent answered for '"
                                + key
                                + "', not its default, and the vault already holds that entry, so"
                                + " nothing was stored; name it with --provider "
                                + key
                                + " to replace it");
            }

            final String said = reading.note();
            final String note =
                    !endless[0]
                            ? said
                            : said.isEmpty()
                                    ? CredentialLifetime.DETAIL
                                    : said + "; " + CredentialLifetime.DETAIL;

            return new Result(
                    Outcome.IMPORTED,
                    key,
                    value.type(),
                    value.secret().length(),
                    reading.source(),
                    note);

        } catch (VaultException ex) {
            return failed(Outcome.FAILED, CliErrors.reason(ex));
        } catch (RuntimeException ex) {
            return failed(Outcome.FAILED, CliErrors.reason(ex));
        }
    }

    /**
     * Returns the name a credential of this agent is stored under when nobody says otherwise.
     *
     * <p>An agent's word alone never replaces another entry: a wrong hint in its config file would
     * put away the person's own key and its grant, and send the foreign secret to that provider.
     */
    private static String defaultName(final PoreloContext context, final InstalledAgent agent) {
        final SelectedProvider selection =
                SelectedProvider.consider(context.providers(), agent.definition(), null)
                        .selection();
        return selection == null ? agent.name() : selection.name();
    }

    /**
     * Says why an agent's answer may not be stored.
     *
     * <p>A credential never lands under the wrong name, so an answer for another provider is
     * refused, and so is one naming none from an agent asked for one: an agent built before it
     * could be asked ignores the question, and what it answers may be anybody's.
     *
     * @param asked The provider asked for, or {@code null}.
     * @param named The provider the answer names, or {@code null}.
     * @return The refusal, or {@code null} to store it.
     */
    private static @Nullable String wrongProvider(
            final @Nullable String asked, final @Nullable String named) {
        if (asked == null || asked.equals(named)) {
            return null;
        }
        return named == null
                ? "this agent cannot say which provider its credential is for, so nothing was"
                        + " stored under '"
                        + asked
                        + "'"
                : "this agent answered for '"
                        + named
                        + "' when asked for '"
                        + asked
                        + "', so nothing was stored";
    }
}
