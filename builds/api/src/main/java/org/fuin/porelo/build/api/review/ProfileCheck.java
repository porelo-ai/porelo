package org.fuin.porelo.build.api.review;

import com.contrastsecurity.sarif.ArtifactChange;
import com.contrastsecurity.sarif.ArtifactLocation;
import com.contrastsecurity.sarif.Fix;
import com.contrastsecurity.sarif.PhysicalLocation;
import com.contrastsecurity.sarif.PropertyBag;
import com.contrastsecurity.sarif.Region;
import com.contrastsecurity.sarif.Replacement;
import com.contrastsecurity.sarif.Result;
import com.contrastsecurity.sarif.Run;
import com.contrastsecurity.sarif.SarifSchema210;
import com.contrastsecurity.sarif.VersionControlDetails;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.review.SarifRefused.Rule;
import org.jspecify.annotations.Nullable;

/**
 * Holds a whole review document from outside to what the review profile says and its schema cannot:
 * run after {@link SarifReading#read}, before anything keeps or shows the document.
 *
 * <p>After a successful {@link #check}, the document is profile 2 of the kind its channel sends,
 * and besides:
 *
 * <ul>
 *   <li>each {@code message.text} is written as SARIF reads plain text (each backslash and square
 *       bracket escaped, each brace doubled), so no text is a link or a placeholder, and is at most
 *       {@value #MAX_TEXT_BYTES} bytes of UTF-8 as given;
 *   <li>no shown string holds a control character (a text and a replacement may hold a line feed
 *       and a tab), a format character (bidi, zero-width, tag), a line or paragraph separator or a
 *       lone surrogate;
 *   <li>each artifact URI is the canonical repository-relative reference of a path that may be
 *       shown;
 *   <li>a pull request run's {@code repositoryUri} and {@code porelo/pullRequest} have the shape a
 *       forge gives them, the pull request under that repository;
 *   <li>each region starts at or before its end and spans at most {@value #MAX_REGION_LINES} lines;
 *   <li>a fix changes its result's file at its result's lines, with at most {@value
 *       #MAX_TEXT_BYTES} bytes, at most the anchored lines plus {@value #EXTRA_REPLACEMENT_LINES}
 *       lines, each at most {@value #MAX_REPLACEMENT_LINE} Unicode scalar values;
 *   <li>a forge account's {@code porelo/createdAt} names a time that was: its day in its month, its
 *       hour at most 23 and its second at most 59.
 * </ul>
 *
 * <p>A refusal names its rule and a JSON Pointer, never the document's text.
 */
public final class ProfileCheck {

    /** How many UTF-8 bytes a text as given, and a replacement, may hold. */
    public static final int MAX_TEXT_BYTES = 8192;

    /** How many lines a region may span. */
    public static final int MAX_REGION_LINES = 100;

    /** How many lines a replacement may hold beyond the lines it replaces. */
    public static final int EXTRA_REPLACEMENT_LINES = 10;

    /** How many Unicode scalar values one line of a replacement may hold. */
    public static final int MAX_REPLACEMENT_LINE = 400;

    /** The fewest path segments of a forge repository's URL. */
    public static final int MIN_REPOSITORY_SEGMENTS = 2;

    /** The most path segments of a forge repository's URL. */
    public static final int MAX_REPOSITORY_SEGMENTS = 8;

    /** The most path segments a pull request's URL adds to its repository's. */
    public static final int MAX_PULL_REQUEST_SEGMENTS = 3;

    /** The most characters of one path segment of a forge URL. */
    public static final int MAX_URL_SEGMENT = 100;

    /** The most characters of a DNS name, and of one label in it. */
    private static final int MAX_DNS_NAME = 253;

    private static final int MAX_DNS_LABEL = 63;

    private static final int MAX_PORT = 65_535;

    private static final String SEGMENT = "[A-Za-z0-9._~-]{1," + MAX_URL_SEGMENT + "}";

    private static final Pattern REPOSITORY =
            Pattern.compile(
                    "https://([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?((?:/"
                            + SEGMENT
                            + "){"
                            + MIN_REPOSITORY_SEGMENTS
                            + ","
                            + MAX_REPOSITORY_SEGMENTS
                            + "})");

    private static final Pattern PULL_REQUEST =
            Pattern.compile(
                    "((?:/"
                            + SEGMENT
                            + "){0,"
                            + (MAX_PULL_REQUEST_SEGMENTS - 1)
                            + "})/[0-9]{1,"
                            + MAX_URL_SEGMENT
                            + "}");

    // Strict, so a day its month lacks is refused, never moved to the next month.
    private static final DateTimeFormatter CREATED_AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                    .withResolverStyle(ResolverStyle.STRICT);

    /** The channel a document came through, which decides the one kind it may send. */
    public enum Channel {

        /** A forge adapter: a profile 2 page of a pull request run, its forge accounts' items. */
        ADAPTER("page"),

        /** A reviewer task's mailbox: a profile 2 submission. */
        TASK("submission");

        private final String kind;

        Channel(final String kind) {
            this.kind = kind;
        }
    }

    private final Set<Rule> skipped;

    /**
     * Returns a checked message text as it was given, SARIF's escaping undone.
     *
     * @param written A {@code message.text} of a document {@link #check} passed.
     * @return The text as given.
     * @throws IllegalArgumentException When it is not written as SARIF reads plain text.
     */
    public static String textAsGiven(final String written) {
        final @Nullable String given = unescaped(written);
        if (given == null) {
            throw new IllegalArgumentException("a message text not written as plain text");
        }
        return given;
    }

    /**
     * Returns the repository-relative path a checked artifact URI names.
     *
     * @param uri An {@code artifactLocation.uri} of a document {@link #check} passed.
     * @return The path, percent-encoding undone.
     * @throws IllegalArgumentException When it is not a canonical reference.
     */
    public static String pathOf(final String uri) {
        final @Nullable String path = path(uri);
        if (path == null) {
            throw new IllegalArgumentException("an artifact URI not in its canonical form");
        }
        return path;
    }

    private ProfileCheck(final Set<Rule> skipped) {
        this.skipped = skipped;
    }

    /**
     * Checks a document {@link SarifReading#read} has read.
     *
     * @param channel What it came through.
     * @param document The document.
     * @return The same document, which holds to everything the class says.
     * @throws SarifRefused When it breaks one of those points.
     */
    public static SarifSchema210 check(final Channel channel, final SarifSchema210 document)
            throws SarifRefused {
        return check(channel, document, Set.of());
    }

    /**
     * Checks a document with the rules given left out, so a test sees a fixture break one alone.
     */
    static SarifSchema210 check(
            final Channel channel, final SarifSchema210 document, final Set<Rule> skipped)
            throws SarifRefused {
        new ProfileCheck(skipped).document(channel, document);
        return document;
    }

    private void document(final Channel channel, final SarifSchema210 document)
            throws SarifRefused {
        final Map<String, Object> properties = bag(document.getProperties());
        if (!"2".equals(properties.get("porelo/profileVersion"))) {
            refuse(Rule.CHANNEL, "/properties/porelo~1profileVersion");
        }
        if (!channel.kind.equals(properties.get("porelo/documentKind"))) {
            refuse(Rule.CHANNEL, "/properties/porelo~1documentKind");
        }
        final List<Run> runs = document.getRuns();
        for (int i = 0; i < runs.size(); i++) {
            run(channel, runs.get(i), "/runs/" + i);
        }
    }

    private void run(final Channel channel, final Run run, final String at) throws SarifRefused {
        final Map<String, Object> properties = bag(run.getProperties());
        final Object pullRequest = properties.get("porelo/pullRequest");
        // A push is Porelo's own; an adapter speaks for a forge's pull request alone.
        if (channel == Channel.ADAPTER && pullRequest == null) {
            refuse(Rule.CHANNEL, at + "/properties");
        }
        shownProperties(properties, at + "/properties");
        if (pullRequest instanceof String url) {
            forgeUrls(run, url, at);
        }
        final List<Result> results = run.getResults();
        for (int j = 0; j < results.size(); j++) {
            result(results.get(j), at + "/results/" + j);
        }
    }

    private void forgeUrls(final Run run, final String pullRequest, final String at)
            throws SarifRefused {
        final String repository =
                run.getVersionControlProvenance().stream()
                        .findFirst()
                        .map(VersionControlDetails::getRepositoryUri)
                        .map(Object::toString)
                        .orElse("");
        final Matcher matcher = REPOSITORY.matcher(repository);
        if (!matcher.matches()
                || !dnsName(matcher.group(1))
                || !port(matcher.group(2))
                || dotSegment(matcher.group(3))) {
            refuse(Rule.URL_SHAPE, at + "/versionControlProvenance/0/repositoryUri");
            return;
        }
        final Matcher rest =
                PULL_REQUEST.matcher(
                        pullRequest.startsWith(repository)
                                ? pullRequest.substring(repository.length())
                                : "");
        if (!rest.matches() || dotSegment(rest.group(1))) {
            refuse(Rule.URL_SHAPE, at + "/properties/porelo~1pullRequest");
        }
    }

    /**
     * Whether a host is a DNS name: at most 253 characters, its labels 1 to 63 characters, none
     * starting or ending with {@code -}, no trailing dot; checked by splitting, since a repeated
     * group in a pattern recurses per label.
     */
    private static boolean dnsName(final String host) {
        if (host.length() > MAX_DNS_NAME) {
            return false;
        }
        for (final String label : host.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > MAX_DNS_LABEL
                    || label.startsWith("-")
                    || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /** Whether a port, when given, is 1 to 65535 without a leading zero. */
    private static boolean port(final @Nullable String port) {
        return port == null || !port.startsWith("0") && Integer.parseInt(port) <= MAX_PORT;
    }

    /** Whether a path holds a {@code .} or {@code ..} segment, which the segments' set admits. */
    private static boolean dotSegment(final String path) {
        for (final String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private void result(final Result result, final String at) throws SarifRefused {
        final Map<String, Object> properties = bag(result.getProperties());
        shownProperties(properties, at + "/properties");
        if (properties.get("porelo/createdAt") instanceof String time) {
            createdAt(time, at + "/properties/porelo~1createdAt");
        }
        message(Objects.requireNonNull(result.getMessage(), "a result's message").getText(), at);
        final PhysicalLocation location =
                Objects.requireNonNull(
                        result.getLocations().get(0).getPhysicalLocation(), "a result's location");
        final String uri = location.getArtifactLocation().getUri();
        final String locationAt = at + "/locations/0/physicalLocation";
        artifactUri(uri, locationAt + "/artifactLocation/uri");
        final Region region = location.getRegion();
        final int lines = region(region, locationAt + "/region");
        final Set<Fix> fixes = result.getFixes();
        if (fixes == null) {
            return;
        }
        int f = 0;
        for (final Fix fix : fixes) {
            int c = 0;
            for (final ArtifactChange change : fix.getArtifactChanges()) {
                fix(change, uri, region, lines, at + "/fixes/" + f + "/artifactChanges/" + c);
                c++;
            }
            f++;
        }
    }

    /** Refuses a time the schema's pattern lets through that no calendar holds. */
    private void createdAt(final String time, final String at) throws SarifRefused {
        try {
            LocalDateTime.parse(time, CREATED_AT);
        } catch (final DateTimeParseException e) {
            refuse(Rule.CREATED_AT, at);
        }
    }

    private void fix(
            final ArtifactChange change,
            final String uri,
            final Region anchor,
            final int lines,
            final String at)
            throws SarifRefused {
        final ArtifactLocation file = change.getArtifactLocation();
        if (!uri.equals(file.getUri())) {
            refuse(Rule.FIX_ANCHOR, at + "/artifactLocation/uri");
        }
        final List<Replacement> replacements = change.getReplacements();
        for (int r = 0; r < replacements.size(); r++) {
            final Replacement replacement = replacements.get(r);
            final String replacementAt = at + "/replacements/" + r;
            final Region deleted = replacement.getDeletedRegion();
            if (!Objects.equals(deleted.getStartLine(), anchor.getStartLine())
                    || !Objects.equals(deleted.getEndLine(), anchor.getEndLine())) {
                refuse(Rule.FIX_ANCHOR, replacementAt + "/deletedRegion");
            }
            replacement(
                    replacement.getInsertedContent().getText(),
                    lines,
                    replacementAt + "/insertedContent/text");
        }
    }

    private void replacement(final String text, final int anchored, final String at)
            throws SarifRefused {
        if (utf8Bytes(text) > MAX_TEXT_BYTES) {
            refuse(Rule.REPLACEMENT_SIZE, at);
        }
        characters(text, true, at);
        final String[] lines = lines(text);
        if (lines.length > (long) anchored + EXTRA_REPLACEMENT_LINES) {
            refuse(Rule.REPLACEMENT_LINES, at);
        }
        for (final String line : lines) {
            if (line.codePointCount(0, line.length()) > MAX_REPLACEMENT_LINE) {
                refuse(Rule.REPLACEMENT_LINE_LENGTH, at);
                return;
            }
        }
    }

    /** A replacement's lines: a line feed ends one, a final one opens none; empty has none. */
    private static String[] lines(final String text) {
        if (text.isEmpty()) {
            return new String[0];
        }
        final String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return body.split("\n", -1);
    }

    /** Checks a region and answers how many lines it spans. */
    private int region(final Region region, final String at) throws SarifRefused {
        final long lines = (long) region.getEndLine() - region.getStartLine() + 1;
        if (lines < 1 || lines > MAX_REGION_LINES) {
            refuse(Rule.REGION, at);
        }
        return (int) Math.max(1, Math.min(lines, Integer.MAX_VALUE));
    }

    private void message(final String written, final String at) throws SarifRefused {
        final String text = at + "/message/text";
        final @Nullable String given = unescaped(written);
        if (given == null) {
            refuse(Rule.MESSAGE_ESCAPE, text);
        }
        final String shown = given == null ? written : given;
        if (utf8Bytes(shown) > MAX_TEXT_BYTES) {
            refuse(Rule.TEXT_SIZE, text);
        }
        characters(shown, true, text);
    }

    /**
     * Returns a message text as given, undoing SARIF's escaping, or null when a character stands
     * unescaped that would make a link or a placeholder.
     */
    private static @Nullable String unescaped(final String written) {
        final StringBuilder given = new StringBuilder(written.length());
        for (int i = 0; i < written.length(); i++) {
            final char c = written.charAt(i);
            final char next = i + 1 < written.length() ? written.charAt(i + 1) : 0;
            if (c == '\\' && (next == '\\' || next == '[' || next == ']')
                    || (c == '{' || c == '}') && next == c) {
                given.append(next);
                i++;
            } else if (c == '\\' || c == '[' || c == ']' || c == '{' || c == '}') {
                return null;
            } else {
                given.append(c);
            }
        }
        return given.toString();
    }

    private void artifactUri(final String uri, final String at) throws SarifRefused {
        final @Nullable String path = path(uri);
        if (path == null
                || path.indexOf('\\') >= 0
                || path.startsWith("/")
                || dotSegment(path)
                || path.contains("//")
                || path.endsWith("/")
                || characterRule(path, false) != null) {
            refuse(Rule.ARTIFACT_URI, at);
        }
    }

    /**
     * Returns the path a URI reference names, or null when it is not the canonical form: every byte
     * but the unreserved ones and {@code /} percent-encoded in uppercase, those never.
     */
    private static @Nullable String path(final String uri) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < uri.length(); i++) {
            final char c = uri.charAt(i);
            if (c == '%') {
                if (i + 2 >= uri.length()) {
                    return null;
                }
                final int high = upperHex(uri.charAt(i + 1));
                final int low = upperHex(uri.charAt(i + 2));
                if (high < 0 || low < 0) {
                    return null;
                }
                final int b = high * 16 + low;
                if (unreservedOrSlash(b)) {
                    return null;
                }
                bytes.write(b);
                i += 2;
            } else if (unreservedOrSlash(c)) {
                bytes.write(c);
            } else {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (final CharacterCodingException e) {
            return null;
        }
    }

    private static int upperHex(final char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    private static boolean unreservedOrSlash(final int c) {
        return c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9'
                || c == '-'
                || c == '.'
                || c == '_'
                || c == '~'
                || c == '/';
    }

    /** Holds every string of a property bag to the one-line rule: each may be shown. */
    private void shownProperties(final Map<String, Object> properties, final String at)
            throws SarifRefused {
        for (final Map.Entry<String, Object> property : properties.entrySet()) {
            if (property.getValue() instanceof String value) {
                // The schema closes each bag, so a name here is the profile's, never the
                // document's.
                characters(
                        value,
                        false,
                        at + "/" + property.getKey().replace("~", "~0").replace("/", "~1"));
            }
        }
    }

    private void characters(final String text, final boolean multiline, final String at)
            throws SarifRefused {
        final @Nullable Rule rule = characterRule(text, multiline);
        if (rule != null) {
            refuse(rule, at);
        }
    }

    /**
     * Returns the rule a character of the text breaks, as a terminal judges what it shows: only a
     * line feed (where lines are allowed) and a tab pass among the controls.
     */
    private static @Nullable Rule characterRule(final String text, final boolean multiline) {
        for (int index = 0; index < text.length(); ) {
            final int point = text.codePointAt(index);
            index += Character.charCount(point);
            if (multiline && (point == '\n' || point == '\t')) {
                continue;
            }
            final int type = Character.getType(point);
            if (point < 0x20 || point >= 0x7f && point <= 0x9f) {
                return Rule.CONTROL_CHARACTER;
            } else if (type == Character.FORMAT || point >= 0xe0000 && point <= 0xe007f) {
                return Rule.FORMAT_CHARACTER;
            } else if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return Rule.SEPARATOR_CHARACTER;
            } else if (type == Character.SURROGATE) {
                return Rule.LONE_SURROGATE;
            }
        }
        return null;
    }

    /** Counts UTF-8 bytes without encoding: a lone surrogate counts three, as its replacement. */
    private static long utf8Bytes(final String text) {
        long bytes = 0;
        for (int index = 0; index < text.length(); ) {
            final int point = text.codePointAt(index);
            index += Character.charCount(point);
            bytes += point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
        }
        return bytes;
    }

    private static Map<String, Object> bag(final @Nullable PropertyBag bag) {
        return bag == null ? Map.of() : bag.getAdditionalProperties();
    }

    private void refuse(final Rule rule, final String at) throws SarifRefused {
        if (!skipped.contains(rule)) {
            throw new SarifRefused(rule, at, "");
        }
    }
}
