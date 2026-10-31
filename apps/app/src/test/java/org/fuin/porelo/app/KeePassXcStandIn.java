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
        /** A wrong master password: nothing is added. */
        REFUSES
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
                    case HOLDS, REFUSES -> "head -n 1 \"$5\" >> \"$here/seen\"";
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
                                : "  echo 'Entry added.'; exit 0 ;;\n")
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
        for (final Path dirs :
                List.of("home", "config", "state", "data", "run").stream()
                        .map(dir::resolve)
                        .toList()) {
            Files.createDirectories(dirs);
        }
    }

    /** The store the verbs run: this stand-in, its terminal a file, waiting this long. */
    KeePassXcKey store(final Duration wait) {
        return new KeePassXcKey(program.toString(), wait, Path.of("/dev/null"), terminal);
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
