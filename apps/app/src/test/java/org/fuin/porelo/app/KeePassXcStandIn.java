package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;

/**
 * A stand-in {@code keepassxc-cli} in a test's directory: it notes each argument list, says whether
 * each attachment arrived on a pipe, looks for a private key file under the account's directories
 * while it holds the key, and gives the key to the test's ssh-agent as KeePassXC's agent
 * integration does when the database is open, now, a second later or never.
 */
final class KeePassXcStandIn {

    /** What KeePassXC does with the key it took. */
    enum Mode {
        /** The database is open: its agent integration serves the key at once. */
        SERVES,
        /** The database is opened a second later. */
        SERVES_LATE,
        /** The database is not open in KeePassXC: the entry is kept, the key not served. */
        HOLDS,
        /**
         * The database is open, and KeePassXC loads the new entry only once it is locked and
         * unlocked: the key is held in memory until the database is unlocked again, at most 30 s
         * and never past the test's directory, so no stand-in outlives its test.
         */
        SERVES_AFTER_UNLOCK,
        /** A wrong master password: nothing is added. */
        REFUSES,
        /** The entry is added, the key refused, and removal takes it to the recycle bin. */
        REFUSES_KEY,
        /** As {@link #REFUSES_KEY}, in a database with no recycle bin. */
        REFUSES_KEY_NO_BIN,
        /** As {@link #REFUSES_KEY}, and the entry cannot be removed either. */
        REFUSES_KEY_AND_REMOVAL,
        /** Nobody types: the first prompt waits. */
        NEVER_TYPED
    }

    private static final String ARMOUR = "-----BEGIN OPENSSH PRIVATE KEY-----";

    final Path dir;
    final Path program;
    final Path database;
    final Path arguments;
    final Path seen;
    final Path during;
    final Path settings;
    final Path terminal;
    final Path gui;
    final Path guiCalls;

    /** The test's own temporary directory, under which the JVM's temporary files lie. */
    private final Path temp = Path.of(System.getProperty("java.io.tmpdir"));

    KeePassXcStandIn(final Path dir, final Mode mode, final String socket) throws IOException {
        this.dir = Files.createDirectories(dir.resolve("keepassxc"));
        program = this.dir.resolve("keepassxc-cli");
        database = Files.writeString(this.dir.resolve("people.kdbx"), "a database\n");
        arguments = this.dir.resolve("arguments");
        seen = this.dir.resolve("seen");
        during = this.dir.resolve("during");
        settings = this.dir.resolve("settings");
        terminal = this.dir.resolve("terminal");
        final String add =
                switch (mode) {
                    case SERVES -> "SSH_AUTH_SOCK=\"$sock\" ssh-add -q - < \"$5\" || exit 1";
                    case SERVES_LATE ->
                            "( sleep 1; SSH_AUTH_SOCK=\"$sock\" ssh-add -q - ) < \"$5\" &";
                    case SERVES_AFTER_UNLOCK ->
                            "( k=$(cat); n=0; while [ ! -e \"$here/unlocked\" ]; do"
                                    + " n=$((n+1)); [ $n -gt 300 ] || [ ! -d \"$here\" ] && exit 0;"
                                    + " sleep 0.1; done;"
                                    + " printf '%s\\n' \"$k\" | SSH_AUTH_SOCK=\"$sock\" ssh-add -q - )"
                                    + " < \"$5\" &";
                    case REFUSES_KEY, REFUSES_KEY_NO_BIN, REFUSES_KEY_AND_REMOVAL -> "exit 1";
                    case HOLDS, REFUSES, NEVER_TYPED -> "head -n 1 \"$5\" >> \"$here/seen\"";
                };
        final String script =
                "#!/bin/sh\n"
                        + "here='"
                        + this.dir
                        + "'\n"
                        + "sock='"
                        + socket
                        + "'\n"
                        + "printf '%s\\n' \"$*\" >> \"$here/arguments\"\n"
                        + "case \"$1\" in\n"
                        + "add)\n"
                        + (mode == Mode.REFUSES
                                ? "  echo 'Invalid credentials' >&2; exit 1 ;;\n"
                                : mode == Mode.NEVER_TYPED
                                        ? "  exec sleep 30 ;;\n"
                                        : "  if grep -qxF -- \"$3\" \"$here/entries\" 2>/dev/null;"
                                                + " then echo 'exists' >&2; exit 1; fi\n"
                                                + "  echo \"$3\" >> \"$here/entries\";"
                                                + " echo 'Entry added.'; exit 0 ;;\n")
                        + "rm)\n"
                        + (mode == Mode.REFUSES_KEY_AND_REMOVAL
                                ? "  exit 1 ;;\n"
                                : "  grep -vxF -- \"$3\" \"$here/entries\" > \"$here/left\";"
                                        + " mv \"$here/left\" \"$here/entries\";"
                                        + (mode == Mode.REFUSES_KEY_NO_BIN
                                                ? " echo \"Successfully deleted entry $3.\";"
                                                : " echo \"Successfully recycled entry $3.\";")
                                        + " exit 0 ;;\n")
                        + "attachment-import)\n"
                        + "  if [ -p \"$5\" ]; then echo \"pipe $4\"; else echo \"no pipe $4\"; fi"
                        + " >> \"$here/seen\"\n"
                        + "  grep -rl -- '"
                        + ARMOUR
                        + "' '"
                        + dir.resolve("home")
                        + "' '"
                        + dir.resolve("config")
                        + "' '"
                        + dir.resolve("state")
                        + "' '"
                        + dir.resolve("data")
                        + "' '"
                        + dir.resolve("run")
                        + "' '"
                        + this.dir
                        + "' 2>/dev/null >> \"$here/during\"\n"
                        + "  find '"
                        + temp
                        + "' -maxdepth 3 -type f -newer '"
                        + database
                        + "' -exec grep -l -- '"
                        + ARMOUR
                        + "' {} + 2>/dev/null >> \"$here/during\"\n"
                        + "  case \"$4\" in\n"
                        + "  id_ed25519) "
                        + add
                        + " ;;\n"
                        + "  KeeAgent.settings) cat \"$5\" > \"$here/settings\" ;;\n"
                        + "  esac ;;\n"
                        + "esac\n";
        Files.writeString(program, script);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        gui = this.dir.resolve("keepassxc");
        guiCalls = this.dir.resolve("gui-calls");
        running(true);
        for (final Path dirs :
                List.of("home", "config", "state", "data", "run").stream()
                        .map(dir::resolve)
                        .toList()) {
            Files.createDirectories(dirs);
        }
    }

    /**
     * Makes the stand-in {@code keepassxc}, KeePassXC's window, running or not. Running, it locks
     * its databases on {@code --lock} and, given a database, stands for the person unlocking it in
     * the window; not running, it says so on {@code --lock}, and a database given to it would start
     * a new window, which it notes.
     */
    void running(final boolean running) throws IOException {
        final String script =
                "#!/bin/sh\n"
                        + "here='"
                        + dir
                        + "'\n"
                        + "printf '%s\\n' \"$*\" >> \"$here/gui-calls\"\n"
                        + "case \"$1\" in\n"
                        + "--lock) "
                        + (running
                                ? "echo 'Databases have been locked.' ;;\n"
                                : "echo 'KeePassXC is not running. No open database to lock' ;;\n")
                        + "*) "
                        + (running
                                ? "touch \"$here/unlocked\"; echo 'Another instance of KeePassXC is"
                                        + " already running.' ;;\n"
                                : "echo started >> \"$here/gui-calls\" ;;\n")
                        + "esac\n";
        Files.writeString(gui, script);
        Files.setPosixFilePermissions(gui, PosixFilePermissions.fromString("rwx------"));
    }

    /** The store the verbs run: this stand-in, its terminal a file, waiting this long. */
    KeePassXcKey store(final Duration wait) {
        return new KeePassXcKey(
                program.toString(),
                gui.toString(),
                wait,
                Path.of("/dev/null"),
                terminal,
                KeePassXcKey.TYPING,
                false);
    }

    /** As {@link #store}, a prompt waiting this long. */
    KeePassXcKey typing(final Duration typing) {
        return new KeePassXcKey(
                program.toString(),
                gui.toString(),
                Duration.ofSeconds(10),
                Path.of("/dev/null"),
                terminal,
                typing,
                false);
    }

    /** As {@link #store}, with its input and error output as given and looked at first. */
    KeePassXcKey on(final Path in, final Path out) {
        return new KeePassXcKey(
                program.toString(),
                gui.toString(),
                Duration.ofSeconds(10),
                in,
                out,
                KeePassXcKey.TYPING,
                true);
    }

    /** The entries the database holds outside its recycle bin. */
    List<String> entries() throws IOException {
        final Path file = dir.resolve("entries");
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    /** Each argument list the stand-in window was run with, one line each. */
    List<String> guiCalls() throws IOException {
        return Files.exists(guiCalls) ? Files.readAllLines(guiCalls) : List.of();
    }

    /** Each argument list the stand-in was run with, one line each. */
    List<String> calls() throws IOException {
        return Files.exists(arguments) ? Files.readAllLines(arguments) : List.of();
    }

    /** The three calls a made key takes, and no other. */
    List<String> madeInto(final String entry) {
        return List.of(
                "add " + database + " " + entry,
                "attachment-import " + database + " " + entry + " id_ed25519 /dev/fd/3",
                "attachment-import " + database + " " + entry + " KeeAgent.settings /dev/fd/3");
    }

    /** What the stand-in noted about each attachment. */
    List<String> seen() throws IOException {
        return Files.exists(seen) ? Files.readAllLines(seen) : List.of();
    }

    /** The agent settings KeePassXC was given, as text. */
    String settings() throws IOException {
        final byte[] bytes = Files.readAllBytes(settings);
        assertThat(bytes).as("UTF-16 with its byte order mark").startsWith(0xFF, 0xFE);
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    /**
     * Each file, found while the stand-in held the key or now, that is a private key file of that
     * public key: in the account's home, config, state, data and runtime directories, the
     * stand-in's own and the JVM's temporary directory.
     */
    List<Path> holding(final String keyLine) throws IOException {
        final List<Path> files = new ArrayList<>();
        if (Files.exists(during)) {
            for (final String line : Files.readAllLines(during)) {
                files.add(Path.of(line));
            }
        }
        for (final String each : List.of("home", "config", "state", "data", "run", "keepassxc")) {
            try (Stream<Path> walked = Files.walk(dir.getParent().resolve(each))) {
                walked.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        final java.nio.file.attribute.FileTime since = Files.getLastModifiedTime(database);
        Files.walkFileTree(
                temp,
                java.util.Set.of(),
                3,
                new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            final Path file,
                            final java.nio.file.attribute.BasicFileAttributes attributes) {
                        if (attributes.isRegularFile()
                                && attributes.lastModifiedTime().compareTo(since) >= 0) {
                            files.add(file);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(
                            final Path file, final IOException ex) {
                        // Another test's file, gone or not this account's.
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
        final List<Path> holding = new ArrayList<>();
        for (final Path file : files) {
            if (holds(file, keyLine)) {
                holding.add(file);
            }
        }
        return holding;
    }

    private static boolean holds(final Path file, final String keyLine) {
        try {
            if (!Files.isReadable(file) || Files.size(file) > 1024 * 1024) {
                return false;
            }
            final String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            if (!text.contains(ARMOUR)) {
                return false;
            }
            final SigningKey key =
                    new SigningKey(
                            Base64.getDecoder().decode(OpenSshPrivateKey.seedBase64(text)), "");
            return PersonCard.keyLine(key).equals(keyLine);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
