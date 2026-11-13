package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
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

    /** A line, its key's comment ignored whatever it holds, a line end too, as ssh-keygen does. */
    private static final Pattern LINE =
            Pattern.compile(
                    "^"
                            + PRINCIPALS
                            + "(?:("
                            + OPTIONS
                            + ")[ \\t]+)?((?:ssh-|ecdsa-|sk-)[^ \\t]+[ \\t]+[^ \\t]+)(?:[ \\t].*)?$",
                    Pattern.DOTALL);

    private static final String UNWRITTEN = "the scratch file ssh-keygen reads cannot be written";

    private static final String UNCHECKED = "the signers file could not be checked";

    /**
     * ssh-keygen's exit code when it cannot parse a line, its {@code SSH_ERR_INVALID_FORMAT}, -4.
     */
    private static final int UNPARSED = 252;

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
     * skips it, when Porelo's own reading finds it malformed for the same reason: one that
     * ssh-keygen parses and Porelo does not, or that ssh-keygen refuses and Porelo reads otherwise,
     * refuses the whole file, as ssh-keygen answers a failed allocation as a line it cannot parse.
     *
     * @param runner Runs ssh-keygen.
     * @param file The file.
     * @param environment All ssh-keygen gets of the environment.
     * @param directory Where the scratch file ssh-keygen reads is written, and removed from.
     * @return The signers its lines name, in their order.
     * @throws IOException If the file cannot be read, holds a line ssh-keygen reads and Porelo does
     *     not, or cannot be checked: ssh-keygen not run, or answering other than by parsing a line
     *     or refusing to parse it.
     * @throws java.io.UncheckedIOException If the scratch file cannot be written or removed.
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
            final boolean whole = parsed(runner, check, entries, environment) == null;
            for (int at = 0; at < entries.size(); at++) {
                final Fault refused =
                        whole ? null : parsed(runner, check, List.of(entries.get(at)), environment);
                if (refused == null) {
                    final AllowedSigner signer = parse(lines.get(at));
                    if (signer == null) {
                        throw new IOException(
                                "an allowed-signers line ssh-keygen reads is not read here");
                    }
                    signers.add(signer);
                } else if (refused != fault(entries.get(at))) {
                    // ssh-keygen answers a failed allocation as a line it cannot parse.
                    throw new IOException(
                            UNCHECKED
                                    + ": ssh-keygen refuses a line read here otherwise ("
                                    + refused
                                    + ")");
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
     * Why ssh-keygen refuses the first of these lines it cannot parse, or {@code null} when it
     * parses each: it stops at the first it cannot, so the lines of a file are asked together and,
     * when that fails, one by one.
     *
     * <p>Only its own refusal to parse a line answers with a fault: it exits with {@value
     * #UNPARSED} and names the file, the line and one of the reasons it parses a line for. Any
     * other answer, a check killed, timed out or not run at all, refuses the whole file, as
     * skipping a line might drop one git reads.
     *
     * @throws IOException If ssh-keygen answers other than by parsing or refusing to parse.
     */
    private static @Nullable Fault parsed(
            final CommandRunner runner,
            final Path check,
            final List<String> entries,
            final java.util.Map<String, String> environment)
            throws IOException {
        write(check, entries);
        final CommandResult result;
        try {
            result =
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
        } catch (final CommandException ex) {
            throw new IOException(UNCHECKED + ": ssh-keygen could not be run", ex);
        }
        if (result.successful() && result.standardError().isBlank()) {
            return null;
        }
        if (result.exitCode() == UNPARSED) {
            final Matcher reason =
                    Pattern.compile(
                                    "\\A"
                                            + Pattern.quote(check.toString())
                                            + ":[1-9][0-9]*+: (invalid options|bad options: |missing"
                                            + " key|invalid key)")
                            .matcher(result.standardError());
            if (reason.lookingAt()) {
                return reason.group(1).endsWith("key") ? Fault.KEY : Fault.OPTIONS;
            }
        }
        throw new IOException(UNCHECKED + ": ssh-keygen exited " + result.exitCode());
    }

    /** Why ssh-keygen cannot parse a line: its options, or its key missing or invalid. */
    enum Fault {
        /** Options not ended, or not ones ssh-keygen knows as given. */
        OPTIONS,
        /** No key, or none ssh-keygen reads. */
        KEY
    }

    /** A key's type and its base64, up to a blank. */
    private static final Pattern KEY = Pattern.compile("([^ \\t]++)[ \\t]++([^ \\t]++)");

    /**
     * An option ssh-keygen knows, its name in any ASCII case as C's {@code strncasecmp} compares
     * it, and for a value the quote that opens it.
     */
    private static final Pattern OPTION =
            Pattern.compile("(?i)cert-authority|(namespaces|valid-after|valid-before)=\"");

    /** A time ssh-keygen may read for an option: a date, to the minute or to the second. */
    private static final Pattern TIME =
            Pattern.compile(
                    "[0-9]{4}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])(?:([01][0-9]|2[0-3])[0-5][0-9](?:[0-5][0-9]|6[01])?)?(?:[Zz]|[Uu][Tt][Cc])?");

    /**
     * Why Porelo's own reading finds a line malformed, as ssh-keygen walks it after its principals:
     * a key, else options up to a blank outside quotes and a key after them, and the options known
     * as given. {@code null} when it finds the line whole.
     *
     * <p>It finds a line malformed only where ssh-keygen surely does, and a line it may not be sure
     * of whole, so ssh-keygen refusing a line this reading finds whole refuses the file.
     *
     * @param entry The line's text after its principals, behind a principal of its own.
     */
    private static @Nullable Fault fault(final String entry) {
        final Matcher head = HEAD.matcher(entry);
        if (!head.lookingAt()) {
            return Fault.KEY;
        }
        final String rest = entry.substring(head.end());
        // ssh-keygen may refuse a key Porelo does not judge, so this reading cannot agree.
        if (key(rest) != Blob.BROKEN) {
            return null;
        }
        int at = 0;
        boolean quoted = false;
        for (; at < rest.length() && (quoted || !spaceOrTab(rest.charAt(at))); at++) {
            if (escapedQuote(rest, at)) {
                at++;
            } else if (rest.charAt(at) == '"') {
                quoted = !quoted;
            }
        }
        if (quoted) {
            return Fault.OPTIONS;
        }
        int key = at;
        while (key < rest.length() && spaceOrTab(rest.charAt(key))) {
            key++;
        }
        if (at == rest.length() || key(rest.substring(key)) == Blob.BROKEN) {
            return Fault.KEY;
        }
        return options(rest.substring(0, at)) ? null : Fault.OPTIONS;
    }

    private static boolean spaceOrTab(final char character) {
        return character == ' ' || character == '\t';
    }

    /** What Porelo's reading knows of a key's blob. */
    private enum Blob {
        /** A key of a type read here, exactly its fields, as ssh-keygen reads it. */
        WHOLE,
        /** No key ssh-keygen reads: no type and base64, a blob of another type, or fields wrong. */
        BROKEN,
        /** A key of a type not read here, whose fields ssh-keygen judges alone. */
        UNJUDGED
    }

    /** The key types Porelo reads itself, and so judges the blob of. */
    private static final String ED25519 = "ssh-ed25519";

    private static final String SK_ED25519 = "sk-ssh-ed25519@openssh.com";

    /**
     * What a text opening with a key holds, its blob read as ssh-keygen reads one: the type, then
     * for an ed25519 key exactly its 32 bytes, and for a security key also its application without
     * a NUL, and nothing left over.
     */
    private static Blob key(final String text) {
        final Matcher match = KEY.matcher(text);
        if (!match.lookingAt()) {
            return Blob.BROKEN;
        }
        // ssh-keygen's base64 skips every C blank within it.
        final String base64 = match.group(2).replaceAll("[\\r\\n\\u000b\\f]", "");
        final byte[] blob;
        try {
            blob = java.util.Base64.getDecoder().decode(base64);
        } catch (final IllegalArgumentException ex) {
            return Blob.BROKEN;
        }
        if (base64.length() % 4 != 0) {
            return Blob.BROKEN;
        }
        final String type = match.group(1);
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(blob);
        final byte[] name = field(buffer);
        if (name == null
                || !new String(name, java.nio.charset.StandardCharsets.ISO_8859_1).equals(type)) {
            return Blob.BROKEN;
        }
        if (!ED25519.equals(type) && !SK_ED25519.equals(type)) {
            return Blob.UNJUDGED;
        }
        final byte[] point = field(buffer);
        if (point == null || point.length != 32) {
            return Blob.BROKEN;
        }
        if (SK_ED25519.equals(type)) {
            final byte[] application = field(buffer);
            if (application == null || contains(application, (byte) 0)) {
                return Blob.BROKEN;
            }
        }
        return buffer.hasRemaining() ? Blob.BROKEN : Blob.WHOLE;
    }

    /** The next SSH wire string of a blob, or {@code null} when the blob ends before it does. */
    private static byte @Nullable [] field(final java.nio.ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return null;
        }
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            return null;
        }
        final byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static boolean contains(final byte[] bytes, final byte wanted) {
        for (final byte each : bytes) {
            if (each == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether options are ones ssh-keygen knows as given: {@code cert-authority}, and {@code
     * namespaces}, {@code valid-after} and {@code valid-before} each once with a quoted value, a
     * time for the latter two, parted by commas.
     *
     * <p>As ssh-keygen walks them, an option may be empty, before the first comma or between two;
     * only a comma that ends the options, a lone one too, is refused.
     */
    private static boolean options(final String text) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        int at = 0;
        while (at < text.length()) {
            final Matcher option = OPTION.matcher(text.substring(at));
            // Where no option opens, the option is empty, which a comma must then end.
            if (option.lookingAt()) {
                final int end = option(text.substring(at), option, seen);
                if (end < 0) {
                    return false;
                }
                at += end;
            }
            if (at == text.length()) {
                return true;
            }
            if (text.charAt(at) != ',' || at + 1 == text.length()) {
                return false;
            }
            at++;
        }
        return true;
    }

    /**
     * Where an option ssh-keygen knows ends in the text it opens, or -1 when its value is not one
     * ssh-keygen reads, or the option was given before.
     */
    private static int option(
            final String rest, final Matcher option, final java.util.Set<String> seen) {
        final String name = option.group(1);
        if (name == null) {
            return option.end();
        }
        if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
            return -1;
        }
        final StringBuilder value = new StringBuilder();
        int end = option.end();
        while (end < rest.length() && rest.charAt(end) != '"') {
            if (escapedQuote(rest, end)) {
                end++;
            }
            value.append(rest.charAt(end));
            end++;
        }
        if (end == rest.length()) {
            return -1;
        }
        if (!"namespaces".equalsIgnoreCase(name) && !TIME.matcher(value).matches()) {
            return -1;
        }
        return end + 1;
    }

    private static void write(final Path check, final List<String> entries) {
        try {
            Files.write(check, entries);
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(UNWRITTEN, ex);
        }
    }
}
