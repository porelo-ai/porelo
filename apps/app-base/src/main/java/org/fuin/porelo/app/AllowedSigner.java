package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * One allowed-signers line, keeping its options separate from the key.
 *
 * @param principal The principal list as ssh-keygen reads it, without the quotes it was given in.
 * @param key The key type and its base64.
 * @param options The options as given, quotes kept.
 */
record AllowedSigner(String principal, String key, List<String> options) {

    /**
     * The principal list as ssh-keygen finds its end: at a blank, which is a space or a tab only,
     * or, from a first quote, at the next quote, whether or not a blank follows it.
     */
    private static final String PRINCIPALS =
            "([^ \\t\"]*+\"[^\"]*+\"|[^ \\t\"]++)(?:(?<=\")[ \\t]*+|[ \\t]++)";

    /**
     * The options as ssh-keygen finds their end: a blank outside quotes, where {@code \"} is a
     * quote that neither opens nor closes one.
     */
    private static final String OPTIONS =
            "(?:\\\\\"?+|[^ \\t\"\\\\]|\"(?:\\\\\"?+|[^\"\\\\])*+\")++";

    private static final Pattern LINE =
            Pattern.compile(
                    "^"
                            + PRINCIPALS
                            + "(?:("
                            + OPTIONS
                            + ")[ \\t]+)?((?:ssh-|ecdsa-|sk-)[^ \\t]+[ \\t]+[^ \\t]+)(?:[ \\t].*)?$");

    private static final String UNWRITTEN = "the scratch file ssh-keygen reads cannot be written";

    static @Nullable AllowedSigner parse(final String line) {
        final Matcher match = LINE.matcher(trimmed(line));
        if (!match.matches() || trimmed(line).startsWith("#")) {
            return null;
        }
        final List<String> options = new ArrayList<>();
        final String text = match.group(2);
        if (text != null) {
            boolean quoted = false;
            int start = 0;
            for (int at = 0; at < text.length(); at++) {
                if (escapedQuote(text, at)) {
                    at++;
                } else if (text.charAt(at) == '"') {
                    quoted = !quoted;
                } else if (text.charAt(at) == ',' && !quoted) {
                    options.add(text.substring(start, at));
                    start = at + 1;
                }
            }
            options.add(text.substring(start));
        }
        return new AllowedSigner(
                match.group(1).replace("\"", ""),
                match.group(3).replaceAll("[ \\t]+", " "),
                List.copyOf(options));
    }

    /** What follows a line's principal list: its options and key, as given. */
    private static String afterPrincipals(final String line) {
        final Matcher match = LINE.matcher(trimmed(line));
        if (!match.matches()) {
            throw new IllegalArgumentException("no allowed-signers line");
        }
        return trimmed(trimmed(line).substring(match.end(1)));
    }

    /** A line without the blanks ssh-keygen skips around it, a space or a tab only. */
    private static String trimmed(final String line) {
        int start = 0;
        int end = line.length();
        while (start < end && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(start, end);
    }

    /** Whether a {@code \"} starts here, which ssh-keygen reads as a quote kept in the value. */
    private static boolean escapedQuote(final String text, final int at) {
        return text.startsWith("\\\"", at);
    }

    /**
     * Reads an allowed-signers file and has ssh-keygen check it, in a chosen environment, its
     * scratch file in a directory of the caller's.
     *
     * @param runner Runs ssh-keygen.
     * @param file The file.
     * @param environment All ssh-keygen gets of the environment.
     * @param directory Where the scratch file ssh-keygen reads is written, and removed from.
     * @return The file's lines.
     * @throws IOException If the file cannot be read or is no allowed-signers file.
     * @throws java.io.UncheckedIOException If the scratch file cannot be written or removed.
     * @throws org.fuin.porelo.core.process.CommandException If ssh-keygen cannot be run.
     */
    static List<String> read(
            final CommandRunner runner,
            final Path file,
            final java.util.Map<String, String> environment,
            final Path directory)
            throws IOException {
        final List<String> lines = Files.readAllLines(file);
        final List<String> entries = entries(lines);
        if (entries.isEmpty()) {
            return lines;
        }
        final Path check;
        try {
            check = Files.createTempFile(directory, "signers-check-", "");
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(UNWRITTEN, ex);
        }
        try {
            write(check, entries);
            final var result =
                    runner.run(
                            Command.of(
                                            "ssh-keygen",
                                            "-Y",
                                            "match-principals",
                                            "-I",
                                            "porelo",
                                            "-f",
                                            check.toString())
                                    .withChosenEnvironment(environment));
            if (!result.successful() || !result.standardError().isBlank()) {
                throw new IOException(
                        "invalid allowed-signers file: " + result.standardError().strip());
            }
        } finally {
            try {
                Files.deleteIfExists(check);
            } catch (final IOException ex) {
                throw new java.io.UncheckedIOException(
                        "the scratch file ssh-keygen read cannot be removed", ex);
            }
        }
        return lines;
    }

    private static void write(final Path check, final List<String> entries) {
        try {
            Files.write(check, entries);
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(UNWRITTEN, ex);
        }
    }

    private static List<String> entries(final List<String> lines) throws IOException {
        final List<String> entries = new ArrayList<>();
        for (final String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            final AllowedSigner parsed = parse(line);
            if (parsed == null) {
                throw new IOException("invalid allowed-signers line");
            }
            entries.add("porelo " + afterPrincipals(line));
        }
        return entries;
    }

    static List<String> read(final CommandRunner runner, final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);
        final List<String> entries = entries(lines);
        if (entries.isEmpty()) {
            return lines;
        }
        final Path check = Files.createTempFile("porelo-signers-check-", "");
        try {
            Files.write(check, entries);
            final var result =
                    runner.run(
                            Command.of(
                                    "ssh-keygen",
                                    "-Y",
                                    "match-principals",
                                    "-I",
                                    "porelo",
                                    "-f",
                                    check.toString()));
            if (!result.successful() || !result.standardError().isBlank()) {
                throw new IOException(
                        "invalid allowed-signers file: " + result.standardError().strip());
            }
        } catch (final RuntimeException ex) {
            throw new IOException("cannot validate the pinned keys: " + ex.getMessage(), ex);
        } finally {
            Files.deleteIfExists(check);
        }
        return lines;
    }
}
