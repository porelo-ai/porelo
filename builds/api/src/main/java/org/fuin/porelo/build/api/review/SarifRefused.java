package org.fuin.porelo.build.api.review;

import java.io.Serial;

/**
 * A review document was refused: it breaks the rule named, at the JSON Pointer named.
 *
 * <p>The message holds the rule and the path alone, never a value or any other part of the
 * document, so it can be logged and shown whatever the document held. A property name in the path
 * that no bundled profile schema knows is written as {@code *}.
 */
public final class SarifRefused extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    /** The rule a document broke. */
    public enum Rule {

        /** The document is more than 1 MiB. */
        DOCUMENT_SIZE("document-size"),

        /** The bytes are not UTF-8 without a byte order mark, or hold a NUL byte. */
        ENCODING("encoding"),

        /**
         * The text is not strict JSON: a comment, a single quote, NaN, a leading zero and so on.
         */
        JSON_SYNTAX("json-syntax"),

        /** JSON nests more than 24 levels deep. */
        NESTING("nesting"),

        /** The document holds more than 100000 JSON tokens. */
        TOKENS("tokens"),

        /**
         * A string is longer than 16384 characters; in a profile 1 document, a result's message
         * text may reach 32768.
         */
        STRING_LENGTH("string-length"),

        /** An artifact location's URI is longer than 2048 characters. */
        URI_LENGTH("uri-length"),

        /** A property name is longer than 128 characters. */
        NAME_LENGTH("name-length"),

        /** A number is written with more than 20 characters. */
        NUMBER_LENGTH("number-length"),

        /** An object names one property twice. */
        DUPLICATE_KEY("duplicate-key"),

        /** Something follows the document's one JSON value. */
        TRAILING_TOKENS("trailing-tokens"),

        /** A run holds more than 100 results. */
        RESULTS("results"),

        /** A result is more than 32 KiB. */
        RESULT_SIZE("result-size"),

        /** The document names no review profile this reader holds a schema for. */
        PROFILE_VERSION("profile-version"),

        /** The document breaks its profile's schema. */
        SCHEMA("schema"),

        /** SARIF's model refused what the schema let through. */
        BINDING("binding"),

        /** The document's profile, kind, producer or run is not what its channel may send. */
        CHANNEL("channel"),

        /**
         * A message text is not written as SARIF reads plain text: a bare square bracket or brace,
         * or a backslash escaping anything but a backslash or a bracket, which would make a link or
         * a placeholder.
         */
        MESSAGE_ESCAPE("message-escape"),

        /** A message text is more than 8192 bytes of UTF-8 as given, before SARIF's escaping. */
        TEXT_SIZE("text-size"),

        /** A shown string holds a control character; a text may hold a line feed and a tab. */
        CONTROL_CHARACTER("control-character"),

        /** A shown string holds a format character: bidi, zero-width or tag. */
        FORMAT_CHARACTER("format-character"),

        /** A shown string holds a line or paragraph separator. */
        SEPARATOR_CHARACTER("separator-character"),

        /** A shown string holds a lone surrogate. */
        LONE_SURROGATE("lone-surrogate"),

        /**
         * An artifact's URI is not the canonical repository-relative reference of a path: an
         * unreserved byte or a slash percent-encoded, a lowercase or broken escape, bytes that are
         * no UTF-8, or a path holding a backslash, a character no person may be shown, a leading
         * slash or an empty, {@code .} or {@code ..} segment.
         */
        ARTIFACT_URI("artifact-uri"),

        /**
         * A pull request run's {@code repositoryUri} is not {@code https://<host>[:<port>]} and 2
         * to 8 path segments, or its {@code porelo/pullRequest} not that URL and 1 to 3 more
         * segments, the last all digits; each segment 1 to 100 unreserved characters and neither
         * {@code .} nor {@code ..}.
         */
        URL_SHAPE("url-shape"),

        /** A region ends before it starts or spans more than 100 lines. */
        REGION("region"),

        /** A fix changes another file or other lines than its result's location. */
        FIX_ANCHOR("fix-anchor"),

        /** A replacement is more than 8192 bytes of UTF-8. */
        REPLACEMENT_SIZE("replacement-size"),

        /** A replacement holds more lines than its anchored lines plus 10. */
        REPLACEMENT_LINES("replacement-lines"),

        /** A replacement's line holds more than 400 Unicode scalar values. */
        REPLACEMENT_LINE_LENGTH("replacement-line-length"),

        /**
         * A forge account's {@code porelo/createdAt} names no time that was: a day its month does
         * not have, an hour past 23 or a second past 59.
         */
        CREATED_AT("created-at");

        private final String text;

        Rule(final String text) {
            this.text = text;
        }

        /**
         * Returns the rule's name as a message writes it.
         *
         * @return It.
         */
        public String text() {
            return text;
        }
    }

    private final Rule rule;

    private final String path;

    /**
     * Constructor.
     *
     * @param rule The rule broken.
     * @param path Where, as a JSON Pointer; empty for the whole document.
     * @param keyword The schema keyword that failed, for {@link Rule#SCHEMA}; empty otherwise.
     */
    SarifRefused(final Rule rule, final String path, final String keyword) {
        super(rule.text() + (keyword.isEmpty() ? "" : " (" + keyword + ")") + " at '" + path + "'");
        this.rule = rule;
        this.path = path;
    }

    /**
     * Returns the rule broken.
     *
     * @return It.
     */
    public Rule rule() {
        return rule;
    }

    /**
     * Returns where the document broke it.
     *
     * @return A JSON Pointer; empty for the whole document.
     */
    public String path() {
        return path;
    }
}
