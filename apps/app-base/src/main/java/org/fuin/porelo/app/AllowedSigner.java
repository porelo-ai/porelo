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
     * The principal list as ssh-keygen finds its end: at a blank, which there is a space, a tab or
     * a carriage return, or, from a first quote, at the next quote, whether or not a blank follows
     * it. The options and the key end at a space or a tab only.
     */
    private static final String PRINCIPALS =
            "([^ \\t\\r\"]*+\"[^\"]*+\"|[^ \\t\\r\"]++)(?:(?<=\")[ \\t\\r]*+|[ \\t\\r]++)";

    /** A line's principal list, the part ssh-keygen reads before it parses the rest. */
    private static final Pattern HEAD = Pattern.compile("^" + PRINCIPALS);

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

    /**
     * A line without the blanks ssh-keygen skips around it: a space, a tab or a carriage return,
     * and no other Unicode space, so a line opening with one is no comment.
     */
    private static String trimmed(final String line) {
        int start = 0;
        int end = line.length();
        while (start < end && blank(line.charAt(start))) {
            start++;
        }
        while (end > start && blank(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(start, end);
    }

    private static boolean blank(final char character) {
        return character == ' ' || character == '\t' || character == '\r';
    }

    /** Whether a {@code \"} starts here, which ssh-keygen reads as a quote kept in the value. */
    private static boolean escapedQuote(final String text, final int at) {
        return text.startsWith("\\\"", at);
    }

    /**
     * Reads an allowed-signers file as ssh-keygen reads it for git, which has it check each line,
     * in a chosen environment, its scratch file in a directory of the caller's.
     *
     * <p>A line ends at a line feed alone. A line ssh-keygen cannot parse names nobody, as git
     * skips it: only one that ssh-keygen parses and Porelo does not refuses the whole file.
     *
     * @param runner Runs ssh-keygen.
     * @param file The file.
     * @param environment All ssh-keygen gets of the environment.
     * @param directory Where the scratch file ssh-keygen reads is written, and removed from.
     * @return The signers its lines name, in their order.
     * @throws IOException If the file cannot be read, or holds a line ssh-keygen reads and Porelo
     *     does not.
     * @throws java.io.UncheckedIOException If the scratch file cannot be written or removed.
     * @throws org.fuin.porelo.core.process.CommandException If ssh-keygen cannot be run.
     */
    static List<AllowedSigner> read(
            final CommandRunner runner,
            final Path file,
            final java.util.Map<String, String> environment,
            final Path directory)
            throws IOException {
        final List<String> lines = new ArrayList<>();
        final List<String> entries = new ArrayList<>();
        for (final String line : Files.readString(file).split("\n", -1)) {
            final String text = trimmed(line);
            final Matcher head = HEAD.matcher(text);
            // A comment, or a line whose principal list ssh-keygen cannot end, names nobody.
            if (!text.startsWith("#") && head.lookingAt()) {
                lines.add(line);
                entries.add("porelo " + text.substring(head.end()));
            }
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        final Path check;
        try {
            check = Files.createTempFile(directory, "signers-check-", "");
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(UNWRITTEN, ex);
        }
        final List<AllowedSigner> signers = new ArrayList<>();
        try {
            final boolean whole = parsed(runner, check, entries, environment);
            for (int at = 0; at < entries.size(); at++) {
                if (whole || parsed(runner, check, List.of(entries.get(at)), environment)) {
                    final AllowedSigner signer = parse(lines.get(at));
                    if (signer == null) {
                        throw new IOException(
                                "an allowed-signers line ssh-keygen reads is not read here");
                    }
                    signers.add(signer);
                }
            }
        } finally {
            try {
                Files.deleteIfExists(check);
            } catch (final IOException ex) {
                throw new java.io.UncheckedIOException(
                        "the scratch file ssh-keygen read cannot be removed", ex);
            }
        }
        return List.copyOf(signers);
    }

    /**
     * Whether ssh-keygen parses each of these lines: it stops at the first it cannot, so the lines
     * of a file are asked together and, when that fails, one by one.
     */
    private static boolean parsed(
            final CommandRunner runner,
            final Path check,
            final List<String> entries,
            final java.util.Map<String, String> environment) {
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
        return result.successful() && result.standardError().isBlank();
    }

    private static void write(final Path check, final List<String> entries) {
        try {
            Files.write(check, entries);
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(UNWRITTEN, ex);
        }
    }
}
