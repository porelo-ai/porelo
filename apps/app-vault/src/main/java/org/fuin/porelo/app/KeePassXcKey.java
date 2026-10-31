package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshAgentClient;
import org.jspecify.annotations.Nullable;

/**
 * Makes a new Ed25519 key straight into a KeePassXC database, for {@code identity create}, {@code
 * rotate} and {@code revoke --lost}, and waits for the agent to serve it.
 *
 * <p>The key exists in this process only, until {@code keepassxc-cli} has taken it as an attachment
 * of a new entry: it is handed over a pipe that is the wrapper shell's standard input, which the
 * shell moves to descriptor 3 before it reopens this process's own terminal as {@code
 * keepassxc-cli}'s standard input, where it asks for the master password itself. No file holds the
 * key and no argument carries it; Porelo never sees the password.
 *
 * @param program The {@code keepassxc-cli} run.
 * @param patience How long the agent is waited for to serve the key.
 * @param terminalIn What {@code keepassxc-cli} reads the password from: this process's own input.
 * @param terminalOut Where {@code keepassxc-cli} prompts and talks: this process's own error
 *     output, so the answer on standard output stays the verb's alone.
 */
record KeePassXcKey(String program, Duration patience, Path terminalIn, Path terminalOut) {

    /** The attachment KeePassXC's agent integration takes the key from. */
    static final String KEY_ATTACHMENT = "id_ed25519";

    /** The attachment that tells KeePassXC's agent integration what to do with the key. */
    static final String SETTINGS_ATTACHMENT = "KeeAgent.settings";

    /** How long the agent is waited for, unless a test says otherwise. */
    static final Duration WAIT = Duration.ofSeconds(60);

    /** How often the agent is asked while it is waited for. */
    private static final Duration POLL = Duration.ofMillis(250);

    /**
     * Moves the pipe on its standard input to descriptor 3, reopens the terminal as standard input
     * and its error output for both outputs, and runs the rest of its arguments.
     */
    private static final String WRAPPER = "exec 3<&0 <\"$1\" >>\"$2\" 2>&1; shift 2; exec \"$@\"";

    /** The path {@code keepassxc-cli} reads the attachment from: the pipe on descriptor 3. */
    private static final String PIPE = "/dev/fd/3";

    /** An entry path: groups and a name, none empty, no control character, no leading dash. */
    private static final java.util.regex.Pattern ENTRY =
            java.util.regex.Pattern.compile("[^/\\p{Cntrl}-][^/\\p{Cntrl}]*(/[^/\\p{Cntrl}]+)*");

    /**
     * The key made and served.
     *
     * @param entry The KeePassXC entry it is in.
     * @param fingerprint Its {@code SHA256:} fingerprint, by which the agent serves it.
     */
    record Made(String entry, String fingerprint) {}

    /**
     * The real one: {@code keepassxc-cli} from {@code PATH}, on this process's own terminal.
     *
     * @return It.
     */
    static KeePassXcKey real() {
        final String self = "/proc/" + ProcessHandle.current().pid() + "/fd/";
        return new KeePassXcKey("keepassxc-cli", WAIT, Path.of(self + "0"), Path.of(self + "2"));
    }

    /**
     * Makes the key into the database and waits for the agent to serve it.
     *
     * @param context The machine: its input says whether a person types into a terminal.
     * @param database The KeePassXC database.
     * @param entry The entry asked for, or {@code null} for one named after the login and now.
     * @param login Whose key it is, its comment.
     * @param socket The agent {@code SSH_AUTH_SOCK} names.
     * @return The entry and the fingerprint the agent serves.
     * @throws IdentityChange.Refused 64 {@code no-terminal} without a terminal and for an entry
     *     path KeePassXC does not take; 65 {@code key-not-in-agent} when no agent answers, {@code
     *     key-store-missing} without {@code keepassxc-cli}, {@code key-store-refused} when it
     *     refuses, and {@code key-not-served} when the agent does not serve the key in time.
     */
    Made make(
            final PoreloContext context,
            final Path database,
            final @Nullable String entry,
            final String login,
            final String socket)
            throws IdentityChange.Refused {
        if (!context.input().terminal()) {
            throw new IdentityChange.Refused(
                    64,
                    "no-terminal",
                    "keepassxc-cli asks for the database's master password on a terminal, and"
                            + " there is none; run this in a terminal");
        }
        final String path = entry == null ? defaultEntry(login) : entry;
        if (!ENTRY.matcher(path).matches() || path.length() > 256) {
            throw new IdentityChange.Refused(
                    64,
                    null,
                    "--entry is GROUP/NAME: groups and a name, none empty, without a leading '-'");
        }
        if (!answers(socket)) {
            throw new IdentityChange.Refused(
                    65,
                    "key-not-in-agent",
                    "no ssh-agent answers at SSH_AUTH_SOCK, so KeePassXC could not give it the"
                            + " key; start one, then ask again");
        }
        final Path file = database.toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-refused",
                    "no KeePassXC database at "
                            + RegistryVerifyCommand.printable(file.toString())
                            + "; nothing was made");
        }
        final String fingerprint = handOver(context, file.toString(), path, login);
        return new Made(path, waitFor(socket, path, fingerprint));
    }

    /**
     * Makes the key, gives it to {@code keepassxc-cli} and forgets it.
     *
     * @return The key's fingerprint.
     */
    private String handOver(
            final PoreloContext context,
            final String database,
            final String entry,
            final String login)
            throws IdentityChange.Refused {
        final SigningKey key = SigningKey.generate(login);
        final String fingerprint = PersonCard.fingerprint(PersonCard.keyLine(key));
        final int added = run(context, new byte[0], "add", database, entry);
        if (added == 126 || added == 127) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-missing",
                    RegistryVerifyCommand.printable(program)
                            + " is not installed; install KeePassXC from the package"
                            + " keepassxc-full, then ask again");
        }
        if (added != 0) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-refused",
                    "keepassxc-cli did not add the entry "
                            + RegistryVerifyCommand.printable(entry)
                            + " (a wrong password, or an entry of that path exists); nothing was"
                            + " kept");
        }
        final byte[] secret = key.openSshPrivateKey();
        final int keyTaken;
        try {
            keyTaken =
                    run(
                            context,
                            secret,
                            "attachment-import",
                            database,
                            entry,
                            KEY_ATTACHMENT,
                            PIPE);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        final int settingsTaken =
                keyTaken != 0
                        ? keyTaken
                        : run(
                                context,
                                settings(),
                                "attachment-import",
                                database,
                                entry,
                                SETTINGS_ATTACHMENT,
                                PIPE);
        if (settingsTaken != 0) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-refused",
                    "keepassxc-cli made the entry "
                            + RegistryVerifyCommand.printable(entry)
                            + " but did not take "
                            + (keyTaken != 0 ? "the key" : "its agent settings")
                            + "; remove that entry in KeePassXC, nothing was kept here");
        }
        return fingerprint;
    }

    /**
     * Runs {@code keepassxc-cli} through the wrapper, the bytes on the pipe it reads as {@code
     * /dev/fd/3}.
     *
     * @return Its exit code.
     */
    private int run(final PoreloContext context, final byte[] piped, final String... arguments) {
        final List<String> all =
                new java.util.ArrayList<>(
                        List.of(
                                "sh",
                                "-c",
                                WRAPPER,
                                "porelo-keepassxc",
                                terminalIn.toString(),
                                terminalOut.toString(),
                                program));
        all.addAll(List.of(arguments));
        try {
            // The pipe's bytes are borrowed: the caller clears them once this returns.
            return context.runner().run(Command.of(all).withBinaryInput(piped)).exitCode();
        } catch (CommandException ex) {
            return 127;
        }
    }

    /** Asks the agent until it serves the key, at most {@link #patience}. */
    private String waitFor(final String socket, final String entry, final String fingerprint)
            throws IdentityChange.Refused {
        final long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            for (final AgentKey each : served(socket)) {
                if (PersonCard.fingerprint(PersonCard.keyLine(each)).equals(fingerprint)) {
                    return fingerprint;
                }
            }
            final long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, POLL.toNanos())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        final Map<String, @Nullable Object> more = new LinkedHashMap<>();
        more.put("entry", entry);
        more.put("fingerprint", fingerprint);
        throw new IdentityChange.Refused(
                65,
                "key-not-served",
                "KeePassXC holds the key "
                        + fingerprint
                        + " in "
                        + RegistryVerifyCommand.printable(entry)
                        + ", but its agent did not serve it within "
                        + patience.toSeconds()
                        + " s: open the database in KeePassXC with its SSH agent integration on,"
                        + " then name the key with --key "
                        + fingerprint,
                more);
    }

    /** Whether an agent answers at the socket, whatever it serves. */
    private static boolean answers(final String socket) {
        try {
            new SshAgentClient(Path.of(socket)).identities();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The keys the agent serves, none when it does not answer. */
    private static List<AgentKey> served(final String socket) {
        try {
            return new SshAgentClient(Path.of(socket)).identities();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** An entry at the database's root named after the login and the time, UTC. */
    private static String defaultEntry(final String login) {
        return "porelo-"
                + login
                + "-"
                + ZonedDateTime.now(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * KeePassXC's agent settings for the entry: the key from its attachment, added to the agent
     * when the database is opened or unlocked and removed when it is locked, never saved to a file.
     * UTF-16 with its byte order mark, as KeePassXC writes the document itself.
     */
    static byte[] settings() {
        final String xml =
                "﻿<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
                        + "<EntrySettings xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
                        + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                        + "  <AllowUseOfSshKey>true</AllowUseOfSshKey>\n"
                        + "  <AddAtDatabaseOpen>true</AddAtDatabaseOpen>\n"
                        + "  <RemoveAtDatabaseClose>true</RemoveAtDatabaseClose>\n"
                        + "  <UseConfirmConstraintWhenAdding>false</UseConfirmConstraintWhenAdding>\n"
                        + "  <UseLifetimeConstraintWhenAdding>false</UseLifetimeConstraintWhenAdding>\n"
                        + "  <LifetimeConstraintDuration>600</LifetimeConstraintDuration>\n"
                        + "  <Location>\n"
                        + "    <SelectedType>attachment</SelectedType>\n"
                        + "    <AttachmentName>"
                        + KEY_ATTACHMENT
                        + "</AttachmentName>\n"
                        + "    <SaveAttachmentToTempFile>false</SaveAttachmentToTempFile>\n"
                        + "    <FileName></FileName>\n"
                        + "  </Location>\n"
                        + "</EntrySettings>\n";
        return xml.getBytes(StandardCharsets.UTF_16LE);
    }
}
