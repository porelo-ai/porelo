package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;

/**
 * Removes a vault entry, or replaces its value, for a caller that cannot be asked anything: the
 * daemon, on behalf of a client of its socket.
 *
 * <p>The same changes {@code porelo vault remove} and {@code porelo vault put} make at the machine,
 * refused by name wherever those commands would need a person: a shut vault, a grant to revoke at
 * its service. The value is never part of a refusal, a message or a {@code toString}.
 */
public final class CredentialChange {

    /** Why a change was refused; nothing was changed. */
    public enum Reason {
        /** There is no vault on this machine. */
        NO_VAULT,
        /** The vault is shut: nothing held opens it, and nobody can be asked. */
        VAULT_LOCKED,
        /** The vault holds no entry of that name. */
        NO_SUCH_CREDENTIAL,
        /** The name is a task's or a grant's own entry, which no listing shows. */
        RESERVED,
        /** Its grant or client registration is ended at the service first, at the machine. */
        GRANTED,
        /** A running task borrows it, and keeps what it read until it stops. */
        BORROWED,
        /** The value given is empty. */
        NO_VALUE,
        /** The value looks like a private key this machine cannot sign with. */
        VALUE_UNREADABLE
    }

    /** A change refused by name, carrying the entry's name and never its value. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Reason reason;

        private final String name;

        private final List<String> tasks;

        Refused(final Reason reason, final String name, final List<String> tasks) {
            super(reason.name() + " " + name);
            this.reason = reason;
            this.name = name;
            this.tasks = List.copyOf(tasks);
        }

        Refused(final Reason reason, final String name) {
            this(reason, name, List.of());
        }

        /**
         * Returns why.
         *
         * @return The reason.
         */
        public Reason reason() {
            return reason;
        }

        /**
         * Returns the entry's name.
         *
         * @return The name.
         */
        public String name() {
            return name;
        }

        /**
         * Returns the running tasks that borrow the entry, for {@link Reason#BORROWED}.
         *
         * @return Their containers, empty otherwise.
         */
        public List<String> tasks() {
            return tasks;
        }
    }

    private final PoreloContext context;

    private final Supplier<List<TaskInventory.Task>> tasks;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param tasks The tasks on it, asked only once the vault is known to hold the entry.
     */
    public CredentialChange(
            final PoreloContext context, final Supplier<List<TaskInventory.Task>> tasks) {
        this.context = context;
        this.tasks = tasks;
    }

    /**
     * Removes an entry, with the client secret a sign-in left beside it.
     *
     * @param name The entry, as the vault's listing names it.
     * @throws Refused If it cannot be removed here; nothing was changed.
     * @throws VaultException If the vault cannot be read or written.
     */
    public void remove(final String name) {
        final VaultFile.Opener opener = opener(name);
        final Map<String, VaultEntry> held = context.vault().read(opener);
        requireCredential(held, name);
        if (VaultRemoveCommand.needsTheService(held, name)) {
            throw new Refused(Reason.GRANTED, name);
        }
        requireNotBorrowed(name);
        context.vault()
                .update(
                        opener,
                        entries -> {
                            // Checked again under the vault's lock: another process may have
                            // removed it since.
                            requireCredential(entries, name);
                            VaultRemoveCommand.removeFrom(entries, name);
                            return entries;
                        });
    }

    /**
     * Replaces an entry's value, keeping its kind and settings.
     *
     * @param name The entry, as the vault's listing names it.
     * @param value The new value.
     * @return Whether the vault was written, {@code false} when the entry already held exactly
     *     this.
     * @throws Refused If it cannot be replaced here; nothing was changed.
     * @throws VaultException If the vault cannot be read or written.
     */
    public boolean replace(final String name, final String value) {
        final VaultFile.Opener opener = opener(name);
        final VaultEntry before = requireCredential(context.vault().read(opener), name);
        final VaultEntry entry = entry(name, value, before);
        requireNotBorrowed(name);
        return context.vault()
                .update(
                        opener,
                        entries -> {
                            requireCredential(entries, name);
                            return VaultPutCommand.replace(entries, name, entry);
                        });
    }

    /** Reads the value as {@code vault put} reads a file's, and keeps the entry's kind. */
    private static VaultEntry entry(
            final String name, final String given, final VaultEntry before) {
        String value = VaultPutCommand.fileValue(given, before.type());
        String type = before.type();
        if (org.fuin.porelo.vault.OpenSshPrivateKey.looksLikeAnyPrivateKey(value)) {
            try {
                value = org.fuin.porelo.vault.StoredKey.toStore(value);
                // Read back before it is stored, as 'vault put' does: a key that cannot sign is
                // refused now rather than at the first fetch.
                org.fuin.porelo.vault.StoredKey.of(value, name);
            } catch (VaultException ex) {
                throw new Refused(Reason.VALUE_UNREADABLE, name);
            }
            type = type == null ? "ssh-key" : type;
        }
        if (value.isEmpty()) {
            throw new Refused(Reason.NO_VALUE, name);
        }
        return new VaultEntry(value, type, before.settings());
    }

    private VaultFile.Opener opener(final String name) {
        if (!context.vault().exists()) {
            throw new Refused(Reason.NO_VAULT, name);
        }
        if (VaultNames.reserved(name)) {
            throw new Refused(Reason.RESERVED, name);
        }
        // Never asked for: a daemon has no terminal, and a prompt nobody sees would hang the call.
        return context.opener().orElseThrow(() -> new Refused(Reason.VAULT_LOCKED, name));
    }

    private static VaultEntry requireCredential(
            final Map<String, VaultEntry> entries, final String name) {
        final VaultEntry entry = VaultNames.credentialsOnly(entries).get(name);
        if (entry == null) {
            throw new Refused(Reason.NO_SUCH_CREDENTIAL, name);
        }
        return entry;
    }

    private void requireNotBorrowed(final String name) {
        final List<String> borrowing = borrowing(name, tasks.get());
        if (!borrowing.isEmpty()) {
            throw new Refused(Reason.BORROWED, name, borrowing);
        }
    }

    /**
     * Names the running tasks that borrow an entry: as a credential beyond their agent's, as their
     * provider's, or by their agent's name, which a task still falls back to.
     *
     * @param name The entry.
     * @param tasks The tasks on this machine.
     * @return Their containers, in the order given.
     */
    static List<String> borrowing(final String name, final List<TaskInventory.Task> tasks) {
        return tasks.stream()
                .filter(TaskInventory.Task::running)
                .filter(
                        task ->
                                task.credentials().containsKey(name)
                                        || name.equals(task.provider())
                                        || (task.provider() == null && name.equals(task.agent())))
                .map(TaskInventory.Task::name)
                .toList();
    }
}
