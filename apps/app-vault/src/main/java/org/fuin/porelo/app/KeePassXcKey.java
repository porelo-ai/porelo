package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
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
 * shell moves to descriptor 3 before it reopens this process's own terminal, where it asks once for
 * the master password and gives it to each {@code keepassxc-cli}. No file holds the key and no
 * argument carries it; this process never sees the password.
 *
 * @param program The {@code keepassxc-cli} run.
 * @param gui KeePassXC itself, its window: locked and asked to show a database whose new entry its
 *     agent does not serve yet.
 * @param patience How long the agent is waited for to serve the key.
 * @param terminalIn What {@code keepassxc-cli} reads the password from: this process's own input.
 * @param terminalOut Where {@code keepassxc-cli} prompts and talks: this process's own error
 *     output, so the answer on standard output stays the verb's alone.
 * @param typing How long a person has for one prompt.
 * @param judging Whether the two are looked at before anything runs; a test's are files.
 * @param running Whether KeePassXC runs; a test says, and a real one looks.
 */
record KeePassXcKey(
        String program,
        String gui,
        Duration patience,
        Path terminalIn,
        Path terminalOut,
        Duration typing,
        boolean judging,
        java.util.function.Function<PoreloContext, KeePassXcNeeds.State> running) {

    /** The attachment KeePassXC's agent integration takes the key from. */
    static final String KEY_ATTACHMENT = "id_ed25519";

    /** The attachment that tells KeePassXC's agent integration what to do with the key. */
    static final String SETTINGS_ATTACHMENT = "KeeAgent.settings";

    /** How long the agent is waited for, unless a test says otherwise. */
    static final Duration WAIT = Duration.ofSeconds(60);

    /**
     * How long the agent is given to serve the key before KeePassXC is locked: long enough for a
     * database KeePassXC loads by itself, short against the wait that follows.
     */
    static final Duration GRACE = Duration.ofSeconds(3);

    /** How long a person has to type the master password at one prompt. */
    static final Duration TYPING = Duration.ofSeconds(120);

    /** What {@code keepassxc --lock} says when it locked a running KeePassXC's databases. */
    private static final String LOCKED = "have been locked";

    /**
     * Hands a database to the running KeePassXC and returns at once: detached, so that a KeePassXC
     * that quit since the lock opens its own window without holding this command until it closes.
     */
    private static final String SHOW = "\"$1\" \"$2\" </dev/null >/dev/null 2>&1 &";

    /** How often the agent is asked while it is waited for. */
    private static final Duration POLL = Duration.ofMillis(250);

    /**
     * One password for the whole making, read here from the terminal with its echo off and given by
     * the shell's own {@code printf}, so that it is on no command line and this process never holds
     * it. The key is the pipe on standard input, moved to descriptor 3; the settings come from an
     * argument, being no secret. Each step says how it ended on what was standard output.
     */
    private static final String SESSION =
            """
            set +xe; unset pw
            exec 3<&0 <"$1" 4>&1 >>"$2" 2>&1
            n=$3; cli=$4; db=$5; entry=$6; settings=$7
            s=$(stty -g 2>/dev/null)
            trap '[ -z "$s" ] || stty "$s"' EXIT; trap 'exit 130' INT TERM
            stty -echo 2>/dev/null
            printf 'Enter password to unlock %s: ' "$db"
            pw=$(timeout --foreground "$n" sh -c 'IFS= read -r p; printf %s "$p"'); c=$?
            [ -z "$s" ] || stty "$s"; echo
            [ "$c" -lt 124 ] || { echo "typed $c" >&4; exit 0; }
            printf '%s\\n' "$pw" | "$cli" add -q "$db" "$entry"; c=$?
            echo "add $c" >&4; [ "$c" -eq 0 ] || exit 0
            printf '%s\\n' "$pw" | "$cli" attachment-import -q "$db" "$entry" id_ed25519 /dev/fd/3; c=$?
            echo "key $c" >&4
            if [ "$c" -eq 0 ]; then
              printf %s "$settings" | base64 -d | { printf '%s\\n' "$pw" | \
                "$cli" attachment-import -q "$db" "$entry" KeeAgent.settings /dev/fd/5; } 5<&0; c=$?
              echo "settings $c" >&4
            fi
            [ "$c" -eq 0 ] && exit 0
            r=$(printf '%s\\n' "$pw" | LC_ALL=C "$cli" rm "$db" "$entry" 2>/dev/null); c=$?
            [ "$c" -eq 0 ] || r=stays
            printf 'rm %s\\n' "$r" >&4
            """;

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
     * The key an id made and the agent has not served yet: a retry takes it, and needs no password.
     *
     * @param entry Its entry.
     * @param fingerprint Its fingerprint. The agent serving it proves it is that key.
     * @param action The change it was made for: no other change takes it.
     */
    private record Kept(String entry, String fingerprint, String action) {}

    /**
     * The real one: {@code keepassxc-cli} from {@code PATH}, on this process's own terminal.
     *
     * @return It.
     */
    static KeePassXcKey real() {
        final String self = "/proc/" + ProcessHandle.current().pid() + "/fd/";
        return new KeePassXcKey(
                "keepassxc-cli",
                "keepassxc",
                WAIT,
                Path.of(self + "0"),
                Path.of(self + "2"),
                TYPING,
                true,
                KeePassXcNeeds::runs);
    }

    /**
     * Makes the key into the database and waits for the agent to serve it.
     *
     * @param context The machine: its input says whether a person types into a terminal.
     * @param database The KeePassXC database.
     * @param entry The entry asked for, or {@code null} for one named after the login and now.
     * @param login Whose key it is, its comment.
     * @param socket The agent {@code SSH_AUTH_SOCK} names.
     * @param action The change this is for, which names an id when none was given.
     * @param id The change's id, or {@code null}: under an id that already made a key, that key is
     *     waited for and no other is made.
     * @return The entry and the fingerprint the agent serves.
     * @throws IdentityChange.Refused 64 {@code no-terminal} or {@code terminal-not-yours} for a
     *     terminal that cannot be asked on, {@code no-password} for a password not typed in time,
     *     and for an entry path KeePassXC does not take; 65 {@code key-not-in-agent} when no agent
     *     answers, {@code key-store-missing} without {@code keepassxc-cli}, {@code
     *     key-store-refused} when it refuses, and {@code key-not-served} when the agent does not
     *     serve the key in time.
     */
    Made make(
            final PoreloContext context,
            final Path database,
            final @Nullable String entry,
            final String login,
            final String socket,
            final String action,
            final @Nullable String id)
            throws IdentityChange.Refused {
        usable(context);
        final @Nullable Kept kept = id == null ? null : kept(context, id);
        if (kept != null && !action.equals(kept.action())) {
            throw new IdentityChange.Refused(
                    64,
                    null,
                    "the id "
                            + id
                            + " made a key for another change, "
                            + kept.action()
                            + "; ask with a new id");
        }
        if (kept != null && entry != null && !entry.equals(kept.entry())) {
            throw new IdentityChange.Refused(
                    64,
                    null,
                    "the id "
                            + id
                            + " made its key into "
                            + AnswerText.printable(kept.entry())
                            + "; ask again without --entry, or with a new id");
        }
        final String path =
                kept != null ? kept.entry() : entry == null ? defaultEntry(login) : entry;
        if (!ENTRY.matcher(path).matches() || path.length() > 256) {
            throw new IdentityChange.Refused(
                    64,
                    null,
                    "--entry is GROUP/NAME: groups and a name, none empty, without a leading '-'");
        }
        ready(context);
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
                            + AnswerText.printable(file.toString())
                            + "; nothing was made");
        }
        final String change = id != null ? id : unusedId(context, action);
        final String fingerprint =
                kept != null
                        ? kept.fingerprint()
                        : handOver(context, file.toString(), path, login, action, change);
        final Duration grace = patience.compareTo(GRACE) < 0 ? patience : GRACE;
        if (served(socket, fingerprint, grace)) {
            forget(context, change);
            return new Made(path, fingerprint);
        }
        // KeePassXC loads an entry added while the database is open only once the database is
        // locked and unlocked: lock it, show the database's unlock view, and wait for the person.
        final boolean locked = lock(context);
        if (!locked) {
            // No KeePassXC runs to load the entry, and nobody can unlock it there: said at once.
            throw notServed(path, file.toString(), fingerprint, false, change);
        }
        show(context, file.toString());
        say(
                "KeePassXC loads the new key only when "
                        + AnswerText.printable(file.toString())
                        + " is unlocked again, so Porelo has locked KeePassXC's databases:"
                        + " unlock "
                        + AnswerText.printable(file.toString())
                        + " in KeePassXC's window; Porelo waits up to "
                        + patience.toSeconds()
                        + " s.\n");
        if (served(socket, fingerprint, patience)) {
            forget(context, change);
            return new Made(path, fingerprint);
        }
        throw notServed(path, file.toString(), fingerprint, true, change);
    }

    /**
     * Refuses before anything runs a terminal nobody could answer on.
     *
     * @param context The machine.
     * @throws IdentityChange.Refused 64 {@code no-terminal} or {@code terminal-not-yours}.
     */
    void usable(final PoreloContext context) throws IdentityChange.Refused {
        if (!context.input().terminal()) {
            throw noTerminal("standard input is not a terminal");
        }
        if (judging) {
            judged(terminalIn, "standard input");
            judged(terminalOut, "standard error");
        }
    }

    private static void judged(final Path descriptor, final String which)
            throws IdentityChange.Refused {
        final Path device;
        try {
            device = Files.readSymbolicLink(descriptor);
        } catch (IOException | UnsupportedOperationException ex) {
            throw noTerminal(
                    "cannot read "
                            + descriptor
                            + " to see what "
                            + which
                            + " is, as under a /proc mounted with hidepid");
        }
        if (!StandardInput.isTerminal(descriptor)) {
            throw noTerminal(which + " is not a terminal");
        }
        // The one name every process has for its own terminal, which belongs to root.
        if (!device.toString().equals("/dev/tty")) {
            try {
                owned(Files.getOwner(device).getName(), System.getProperty("user.name"), which);
            } catch (IOException ex) {
                throw noTerminal(which + " is a terminal that cannot be read");
            }
        }
        if (!Files.isReadable(device) || !Files.isWritable(device)) {
            throw noTerminal(which + " is a terminal this account cannot open");
        }
    }

    /**
     * Refuses a terminal of another account, where {@code su} or {@code sudo -u} leaves it.
     *
     * @param owner Whose it is.
     * @param me Who this is.
     * @param which What it is to this process.
     * @throws IdentityChange.Refused 64 {@code terminal-not-yours}.
     */
    static void owned(final String owner, final String me, final String which)
            throws IdentityChange.Refused {
        if (!owner.equals(me)) {
            throw new IdentityChange.Refused(
                    64,
                    "terminal-not-yours",
                    which
                            + " is a terminal of "
                            + AnswerText.printable(owner)
                            + ", and this runs as "
                            + AnswerText.printable(me)
                            + ": keepassxc-cli could not ask there; log in as "
                            + AnswerText.printable(me)
                            + " rather than through su or sudo");
        }
    }

    /** Says what is missing before any password is asked; what cannot be told does not refuse. */
    void ready(final PoreloContext context) throws IdentityChange.Refused {
        final List<KeePassXcNeeds.Need> missing =
                KeePassXcNeeds.read(context, program, running).stream()
                        .filter(need -> need.state() == KeePassXcNeeds.State.MISSING)
                        .filter(need -> !KeePassXcNeeds.AGENT.equals(need.name()))
                        .toList();
        if (missing.isEmpty()) {
            return;
        }
        final StringBuilder words = new StringBuilder();
        for (final KeePassXcNeeds.Need need : missing) {
            words.append(need.detail()).append(": ").append(need.next()).append("; ");
        }
        throw new IdentityChange.Refused(
                65,
                missing.getFirst().absent() ? "key-store-missing" : "key-store-not-ready",
                words + "no password was asked and nothing changed; then ask again");
    }

    private static IdentityChange.Refused noTerminal(final String why) {
        return new IdentityChange.Refused(
                64,
                "no-terminal",
                "keepassxc-cli asks for the database's master password on a terminal, and "
                        + why
                        + "; use --key with a key your SSH agent serves, or run this in a terminal, with neither redirected");
    }

    private static Path keptDirectory(final PoreloContext context) {
        return IdentityOperation.directory(context).resolveSibling("keepassxc");
    }

    private static Path keptFile(final PoreloContext context, final String id) {
        return keptDirectory(context).resolve(id);
    }

    private static @Nullable Kept kept(final PoreloContext context, final String id)
            throws IdentityChange.Refused {
        final Path file = keptFile(context, id);
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() != 3) {
                throw new IOException(file + " is not the note of a key");
            }
            return new Kept(lines.get(0), lines.get(1), lines.get(2));
        } catch (IOException ex) {
            throw new IdentityChange.Refused(ex);
        }
    }

    private static void keep(final PoreloContext context, final String id, final Kept kept)
            throws IdentityChange.Refused {
        final Path file = keptFile(context, id);
        try {
            IdentityStore.privateDirectory(context.paths().xdg().state(), keptDirectory(context));
            Files.writeString(
                    file,
                    kept.entry() + "\n" + kept.fingerprint() + "\n" + kept.action() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IdentityChange.Refused(ex);
        }
    }

    private static void forget(final PoreloContext context, final String id) {
        try {
            Files.deleteIfExists(keptFile(context, id));
        } catch (IOException ex) {
            // The note names a key the agent serves: a retry under this id only waits for it.
        }
    }

    /** An id free of an operation and of a key waited for: an earlier run may hold either. */
    private static String unusedId(final PoreloContext context, final String action) {
        final String id = IdentityOperation.newId(context, action, IdentityChange.today());
        String free = id;
        for (int more = 2; Files.exists(keptFile(context, free)); more++) {
            free = id + "-" + more;
        }
        return free;
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
            final String login,
            final String action,
            final String id)
            throws IdentityChange.Refused {
        final SigningKey key = SigningKey.generate(login);
        final String fingerprint = PersonCard.fingerprint(PersonCard.keyLine(key));
        keep(context, id, new Kept(entry, fingerprint, action));
        final byte[] secret = key.openSshPrivateKey();
        final Map<String, String> ended;
        try {
            ended = session(context, secret, database, entry);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if ("0".equals(ended.get("settings"))) {
            return fingerprint;
        }
        forget(context, id);
        if (ended.containsKey("typed")) {
            throw new IdentityChange.Refused(
                    64,
                    "no-password",
                    "the master password was not typed within "
                            + typing.toSeconds()
                            + " s; nothing was kept");
        }
        final String added = ended.getOrDefault("add", "127");
        if (added.equals("126") || added.equals("127")) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-missing",
                    AnswerText.printable(program)
                            + " is not installed; install KeePassXC from the package"
                            + " keepassxc-full, then ask again");
        }
        if (!added.equals("0")) {
            throw new IdentityChange.Refused(
                    65,
                    "key-store-refused",
                    "keepassxc-cli did not add the entry "
                            + AnswerText.printable(entry)
                            + " (a wrong password, or an entry of that path exists); nothing was"
                            + " kept");
        }
        final String shown = String.valueOf(AnswerText.printable(entry));
        final String removed = ended.getOrDefault("rm", "stays");
        throw new IdentityChange.Refused(
                65,
                "key-store-refused",
                "keepassxc-cli made the entry "
                        + shown
                        + " but did not take "
                        + ("0".equals(ended.get("key")) ? "its agent settings" : "the key")
                        + "; "
                        + (removed.startsWith("Successfully recycled")
                                ? "it went to the database's recycle bin, where the private key"
                                        + " stays until the bin is emptied"
                                : removed.equals("stays")
                                        ? "it is not removed: remove " + shown + " in KeePassXC"
                                        : "it was removed"));
    }

    /**
     * Runs the one session, the key on the pipe it reads as {@code /dev/fd/3}.
     *
     * @return How each step ended, by the step's name; empty when it could not run.
     */
    private Map<String, String> session(
            final PoreloContext context,
            final byte[] secret,
            final String database,
            final String entry) {
        final Map<String, String> ended = new LinkedHashMap<>();
        try {
            // The pipe's bytes are borrowed: the caller clears them once this returns.
            final CommandResult result =
                    context.runner()
                            .run(
                                    Command.of(
                                                    "sh",
                                                    "-c",
                                                    SESSION,
                                                    "porelo-keepassxc",
                                                    terminalIn.toString(),
                                                    terminalOut.toString(),
                                                    String.valueOf(typing.toSeconds()),
                                                    program,
                                                    database,
                                                    entry,
                                                    Base64.getEncoder().encodeToString(settings()))
                                            .withBinaryInput(secret));
            result.standardOutput()
                    .lines()
                    .forEach(
                            line -> {
                                final int space = line.indexOf(' ');
                                if (space > 0) {
                                    ended.put(line.substring(0, space), line.substring(space + 1));
                                }
                            });
        } catch (CommandException ex) {
            // Nothing ran, which the caller reads as no keepassxc-cli.
        }
        return ended;
    }

    /** Asks the agent until it serves the key, at most {@code within}. */
    private static boolean served(
            final String socket, final String fingerprint, final Duration within) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            for (final AgentKey each : served(socket)) {
                if (IdentityFacts.fingerprint(each.keyBlob()).equals(fingerprint)) {
                    return true;
                }
            }
            final long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, POLL.toNanos())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Locks the running KeePassXC's databases, which is what makes it load a new entry once one is
     * unlocked again. Its words tell a running KeePassXC from none; it exits 0 either way.
     *
     * @return Whether a running KeePassXC locked its databases.
     */
    private boolean lock(final PoreloContext context) {
        try {
            final CommandResult result =
                    context.runner()
                            .run(Command.of(gui, "--lock").withEnvironment(Map.of("LC_ALL", "C")));
            return (result.standardOutput() + result.standardError()).contains(LOCKED);
        } catch (CommandException ex) {
            return false;
        }
    }

    /** Asks the running KeePassXC to show the database, whose unlock view the person then uses. */
    private void show(final PoreloContext context, final String database) {
        try {
            context.runner().run(Command.of("sh", "-c", SHOW, "porelo-keepassxc", gui, database));
        } catch (CommandException ex) {
            // Not shown: the person unlocks it in KeePassXC as the words say.
        }
    }

    /** Writes a line where keepassxc-cli talks to the person: their terminal. */
    private void say(final String line) {
        try {
            Files.writeString(
                    terminalOut,
                    line,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            // The answer's words say the same.
        }
    }

    /** The refusal for a key the agent did not serve in time, saying what the person does next. */
    private IdentityChange.Refused notServed(
            final String entry,
            final String database,
            final String fingerprint,
            final boolean locked,
            final String id) {
        final Map<String, @Nullable Object> more = new LinkedHashMap<>();
        more.put("id", id);
        more.put("entry", entry);
        more.put("fingerprint", fingerprint);
        final String shown = String.valueOf(AnswerText.printable(database));
        return new IdentityChange.Refused(
                65,
                "key-not-served",
                "KeePassXC holds the key "
                        + fingerprint
                        + " in "
                        + AnswerText.printable(entry)
                        + (locked
                                ? ", but its agent did not serve it within "
                                        + patience.toSeconds()
                                        + " s: Porelo locked KeePassXC's databases; unlock "
                                        + shown
                                        + " in KeePassXC's window with its SSH agent integration on"
                                : ", and KeePassXC is not running to give it to the agent: open "
                                        + shown
                                        + " in KeePassXC with its SSH agent integration on")
                        + ", then ask again with --id "
                        + id
                        + ", which makes no second key, or name the key with --key "
                        + fingerprint,
                more);
    }

    /** Whether an agent answers at the socket, whatever it serves. */
    static boolean answers(final String socket) {
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
