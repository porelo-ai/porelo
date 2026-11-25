package org.fuin.porelo.build.api.review;

import com.contrastsecurity.sarif.SarifSchema210;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.build.api.review.SarifRefused.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The one reader of a review document from outside: a forge adapter's page, a task's submission.
 *
 * <p>A document is held to the review profile's bounds on its bytes and its token stream, then to
 * the bundled schema of the profile its {@code porelo/profileVersion} names, and only then bound to
 * SARIF's model. After a successful {@link #read}, the document:
 *
 * <ul>
 *   <li>is at most 1 MiB of UTF-8 without a byte order mark, and strict JSON: one object, nothing
 *       after it, no comment, single quote, NaN or leading zero;
 *   <li>nests at most 24 levels, holds at most 100000 tokens, no property name longer than 128
 *       characters, no number written longer than 20 characters, no artifact location's URI longer
 *       than 2048 characters, and no string longer than 16384 characters but, in a profile 1
 *       document alone, a result's {@code message.text} of at most 32768;
 *   <li>names no property twice in one object;
 *   <li>holds at most 100 results per run, each at most 32 KiB as written;
 *   <li>holds to profile 1's or profile 2's schema, whose objects are closed, without fetching
 *       anything;
 *   <li>bound to {@link SarifSchema210} without coercion, enum fallback or polymorphic typing.
 * </ul>
 *
 * <p>Not checked here are the profile's bounds no schema can state: a text's bytes as given and its
 * characters, a region's lines, a replacement's lines. The caller checks those on the model.
 */
public final class SarifReading {

    /** A document's bound in bytes. */
    static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    /** A result's bound in bytes, as written. */
    static final int MAX_RESULT_BYTES = 32 * 1024;

    /** The most results a run holds. */
    static final int MAX_RESULTS = 100;

    static final int MAX_NESTING = 24;

    static final int MAX_TOKENS = 100_000;

    static final int MAX_STRING = 16_384;

    // Profile 1 bounds a message text's written form at 32768, where escaping doubles a brace.
    static final int MAX_MESSAGE_TEXT = 32_768;

    // The one profile whose message text may pass the string bound.
    private static final String LONG_TEXT_PROFILE = "1";

    static final int MAX_NAME = 128;

    // Profile 1's URI pattern recurses per character in the JDK's engine; this keeps it in a stack.
    static final int MAX_URI = 2048;

    static final int MAX_NUMBER = 20;

    private static final String REVIEW = "/org/fuin/porelo/build/api/review/";

    private static final String PROFILE_VERSION = "porelo/profileVersion";

    private static final JsonFactory FACTORY =
            JsonFactory.builder()
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxDocumentLength(MAX_DOCUMENT_BYTES)
                                    .maxNestingDepth(MAX_NESTING)
                                    .maxTokenCount(MAX_TOKENS)
                                    .maxStringLength(MAX_MESSAGE_TEXT)
                                    .maxNameLength(MAX_NAME)
                                    .maxNumberLength(MAX_NUMBER)
                                    .build())
                    // A location's excerpt would carry the document's text into a message.
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                    .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                    .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                    .disable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                    .disable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
                    .disable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
                    .disable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
                    .disable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
                    .disable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    .disable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                    .disable(JsonReadFeature.ALLOW_MISSING_VALUES)
                    .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                    .build();

    private static final ObjectMapper MAPPER = mapper();

    private static final Map<String, JsonSchema> PROFILES =
            Map.of(
                    "1",
                    schema("review-profile-1.schema.json"),
                    "2",
                    schema("review-profile-2.schema.json"));

    // Every property name a profile allows; any other is the document's own text, never repeated.
    private static final Set<String> KNOWN_NAMES = knownNames();

    private SarifReading() {}

    /**
     * Reads a review document from outside.
     *
     * @param document The document's bytes, as received.
     * @return SARIF's model of it, which holds to everything the class says.
     * @throws SarifRefused When the document breaks a bound, its profile's schema or the model.
     */
    public static SarifSchema210 read(final byte[] document) throws SarifRefused {
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new SarifRefused(Rule.DOCUMENT_SIZE, "", "");
        }
        requireUtf8(document);
        scan(document);
        final JsonNode tree;
        try {
            tree = MAPPER.readTree(document);
        } catch (final IOException e) {
            // The scan has passed the same parser over the same bytes.
            throw new IllegalStateException("the scan let through what the tree refuses");
        }
        final JsonNode version = tree.path("properties").path(PROFILE_VERSION);
        final JsonSchema schema = version.isTextual() ? PROFILES.get(version.textValue()) : null;
        if (schema == null) {
            throw new SarifRefused(Rule.PROFILE_VERSION, "/properties/porelo~1profileVersion", "");
        }
        final Iterator<ValidationMessage> violations = schema.validate(tree).iterator();
        if (violations.hasNext()) {
            final ValidationMessage violation = violations.next();
            throw new SarifRefused(
                    Rule.SCHEMA,
                    pointer(violation.getInstanceLocation()),
                    String.valueOf(violation.getType()));
        }
        try {
            return MAPPER.treeToValue(tree, SarifSchema210.class);
        } catch (final JsonMappingException e) {
            throw new SarifRefused(Rule.BINDING, references(e.getPath()), "");
        } catch (final IOException e) {
            throw new SarifRefused(Rule.BINDING, "", "");
        }
    }

    private static void requireUtf8(final byte[] document) throws SarifRefused {
        // A NUL byte is how JSON in UTF-16 or UTF-32 begins; Jackson would detect and read it.
        final boolean bom =
                document.length >= 3
                        && document[0] == (byte) 0xEF
                        && document[1] == (byte) 0xBB
                        && document[2] == (byte) 0xBF;
        boolean nul = false;
        for (final byte b : document) {
            nul |= b == 0;
        }
        if (bom || nul) {
            throw new SarifRefused(Rule.ENCODING, "", "");
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(document));
        } catch (final CharacterCodingException e) {
            throw new SarifRefused(Rule.ENCODING, "", "");
        }
    }

    /** Passes over the token stream once, holding it to every bound before anything is built. */
    private static void scan(final byte[] document) throws SarifRefused {
        try (JsonParser parser = FACTORY.createParser(document)) {
            final Deque<Set<String>> names = new ArrayDeque<>();
            long tokens = 0;
            @Nullable JsonStreamContext result = null;
            long resultStart = 0;
            // The profile may be named after the texts: a long one is judged when the scan ends.
            @Nullable String profile = null;
            @Nullable String longText = null;
            try {
                for (JsonToken token = parser.nextToken();
                        token != null;
                        token = parser.nextToken()) {
                    tokens++;
                    if (tokens > MAX_TOKENS) {
                        throw refused(Rule.TOKENS, parser.getParsingContext());
                    }
                    final JsonStreamContext context = parser.getParsingContext();
                    switch (token) {
                        case START_OBJECT -> {
                            names.push(new HashSet<>());
                            if (result == null && isResult(context)) {
                                if (context.getParent().getCurrentIndex() >= MAX_RESULTS) {
                                    throw refused(Rule.RESULTS, context);
                                }
                                result = context;
                                resultStart = parser.currentTokenLocation().getByteOffset();
                            }
                        }
                        case END_OBJECT -> names.pop();
                        case FIELD_NAME -> {
                            final String name = parser.currentName();
                            if (name.length() > MAX_NAME) {
                                throw refusedAt(Rule.NAME_LENGTH, context);
                            }
                            if (!Objects.requireNonNull(names.peek(), "an open object").add(name)) {
                                throw refusedAt(Rule.DUPLICATE_KEY, context);
                            }
                        }
                        case VALUE_STRING -> {
                            final boolean text = isMessageText(context);
                            final int length = parser.getTextLength();
                            if (length > (text ? MAX_MESSAGE_TEXT : MAX_STRING)) {
                                throw refused(Rule.STRING_LENGTH, context);
                            }
                            if (length > MAX_URI && isArtifactUri(context)) {
                                throw refused(Rule.URI_LENGTH, context);
                            }
                            if (text && length > MAX_STRING && longText == null) {
                                longText = pointer(context, null);
                            }
                            if (isProfileVersion(context)) {
                                profile = parser.getText();
                            }
                        }
                        case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                            if (parser.getTextLength() > MAX_NUMBER) {
                                throw refused(Rule.NUMBER_LENGTH, context);
                            }
                        }
                        default -> {
                            // Arrays, literals: bounded by nesting and tokens alone.
                        }
                    }
                    if (result != null) {
                        if (parser.currentLocation().getByteOffset() - resultStart
                                > MAX_RESULT_BYTES) {
                            throw refusedAt(Rule.RESULT_SIZE, result);
                        }
                        if (token == JsonToken.END_OBJECT && context == result.getParent()) {
                            result = null;
                        }
                    }
                    if (context.inRoot() && (token.isStructEnd() || token.isScalarValue())) {
                        requireEnd(parser);
                        if (longText != null && !LONG_TEXT_PROFILE.equals(profile)) {
                            throw new SarifRefused(Rule.STRING_LENGTH, longText, "");
                        }
                        return;
                    }
                }
                throw new SarifRefused(Rule.JSON_SYNTAX, "", "");
            } catch (final StreamConstraintsException e) {
                final Rule rule = constraint(e);
                // A name too long is refused before it is a name: the object is where it stands.
                throw rule == Rule.NAME_LENGTH
                        ? refusedAt(rule, parser.getParsingContext())
                        : refused(rule, parser.getParsingContext());
            } catch (final IOException e) {
                throw refused(Rule.JSON_SYNTAX, parser.getParsingContext());
            }
        } catch (final IOException e) {
            // Closing a parser over a byte array cannot fail.
            throw new IllegalStateException("closing a parser over bytes failed");
        }
    }

    private static void requireEnd(final JsonParser parser) throws SarifRefused {
        try {
            if (parser.nextToken() != null) {
                throw new SarifRefused(Rule.TRAILING_TOKENS, "", "");
            }
        } catch (final IOException e) {
            throw new SarifRefused(Rule.TRAILING_TOKENS, "", "");
        }
    }

    /** Whether an object is a run's result: {@code /runs/<i>/results/<j>}. */
    private static boolean isResult(final JsonStreamContext object) {
        final @Nullable JsonStreamContext results = parent(object);
        final @Nullable JsonStreamContext run = parent(results);
        final @Nullable JsonStreamContext runs = parent(run);
        final @Nullable JsonStreamContext document = parent(runs);
        final @Nullable JsonStreamContext root = parent(document);
        return object.inObject()
                && results != null
                && results.inArray()
                && run != null
                && run.inObject()
                && "results".equals(run.getCurrentName())
                && runs != null
                && runs.inArray()
                && document != null
                && document.inObject()
                && "runs".equals(document.getCurrentName())
                && root != null
                && root.inRoot();
    }

    /** Whether a string is a result's message text: {@code /runs/<i>/results/<j>/message/text}. */
    private static boolean isMessageText(final JsonStreamContext context) {
        final @Nullable JsonStreamContext result = parent(context);
        return context.inObject()
                && "text".equals(context.getCurrentName())
                && result != null
                && "message".equals(result.getCurrentName())
                && isResult(result);
    }

    /** Whether a string is an artifact location's {@code uri}, wherever the location stands. */
    private static boolean isArtifactUri(final JsonStreamContext context) {
        final @Nullable JsonStreamContext location = parent(context);
        return context.inObject()
                && "uri".equals(context.getCurrentName())
                && location != null
                && "artifactLocation".equals(location.getCurrentName());
    }

    /** Whether a string is the document's {@code /properties/porelo~1profileVersion}. */
    private static boolean isProfileVersion(final JsonStreamContext context) {
        final @Nullable JsonStreamContext document = parent(context);
        final @Nullable JsonStreamContext root = parent(document);
        return context.inObject()
                && PROFILE_VERSION.equals(context.getCurrentName())
                && document != null
                && document.inObject()
                && "properties".equals(document.getCurrentName())
                && root != null
                && root.inRoot();
    }

    private static @Nullable JsonStreamContext parent(final @Nullable JsonStreamContext context) {
        return context == null ? null : context.getParent();
    }

    private static Rule constraint(final StreamConstraintsException e) {
        // Jackson names the limit by its getter; the message is never passed on.
        final String message = String.valueOf(e.getOriginalMessage());
        if (message.contains("getMaxNestingDepth")) {
            return Rule.NESTING;
        } else if (message.contains("getMaxTokenCount")) {
            return Rule.TOKENS;
        } else if (message.contains("getMaxStringLength")) {
            return Rule.STRING_LENGTH;
        } else if (message.contains("getMaxNameLength")) {
            return Rule.NAME_LENGTH;
        } else if (message.contains("getMaxNumberLength")) {
            return Rule.NUMBER_LENGTH;
        } else if (message.contains("getMaxDocumentLength")) {
            return Rule.DOCUMENT_SIZE;
        }
        return Rule.JSON_SYNTAX;
    }

    private static SarifRefused refused(final Rule rule, final JsonStreamContext at) {
        return new SarifRefused(rule, pointer(at, null), "");
    }

    /** A refusal at an object, never naming the property it was at, which may be the document's. */
    private static SarifRefused refusedAt(final Rule rule, final JsonStreamContext object) {
        return new SarifRefused(rule, pointer(object, object), "");
    }

    private static String pointer(
            final JsonStreamContext at, final @Nullable JsonStreamContext stop) {
        final List<Object> segments = new ArrayList<>();
        for (JsonStreamContext c = at; c != null && !c.inRoot(); c = c.getParent()) {
            if (c.inArray()) {
                segments.addFirst(Math.max(0, c.getCurrentIndex()));
            } else if (c.getCurrentName() != null && c != stop) {
                segments.addFirst(c.getCurrentName());
            }
        }
        return pointer(segments);
    }

    private static String pointer(final JsonNodePath path) {
        final List<Object> segments = new ArrayList<>();
        for (int i = 0; i < path.getNameCount(); i++) {
            segments.add(path.getElement(i));
        }
        return pointer(segments);
    }

    private static String references(final List<JsonMappingException.Reference> path) {
        final List<Object> segments = new ArrayList<>();
        for (final JsonMappingException.Reference reference : path) {
            segments.add(
                    reference.getFieldName() != null
                            ? reference.getFieldName()
                            : (Object) reference.getIndex());
        }
        return pointer(segments);
    }

    private static String pointer(final List<Object> segments) {
        final StringBuilder pointer = new StringBuilder();
        for (final Object segment : segments) {
            pointer.append('/');
            if (segment instanceof String name) {
                pointer.append(
                        KNOWN_NAMES.contains(name)
                                ? name.replace("~", "~0").replace("/", "~1")
                                : "*");
            } else {
                pointer.append(segment);
            }
        }
        return pointer.toString();
    }

    private static ObjectMapper mapper() {
        final ObjectMapper mapper =
                JsonMapper.builder(FACTORY)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                        .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                        .disable(
                                DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                        .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                        .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                        .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                        .disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        // Admits no type at all: nothing in a document picks a class.
                        .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder().build())
                        .build();
        mapper.coercionConfigDefaults()
                .setAcceptBlankAsEmpty(false)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyArray, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyObject, CoercionAction.Fail);
        return mapper;
    }

    private static JsonNode schemaNode(final String name) {
        try (InputStream in = SarifReading.class.getResourceAsStream(REVIEW + name)) {
            return MAPPER.readTree(Objects.requireNonNull(in, () -> "the bundled schema " + name));
        } catch (final IOException e) {
            throw new IllegalStateException("the bundled schema " + name + " cannot be read", e);
        }
    }

    private static JsonSchema schema(final String name) {
        // Only the bundled schema: nothing a document or a schema names is ever fetched.
        return JsonSchemaFactory.getInstance(
                        SpecVersion.VersionFlag.V202012,
                        builder ->
                                builder.schemaLoaders(
                                        loaders -> loaders.add(DisallowSchemaLoader.getInstance())))
                .getSchema(
                        schemaNode(name),
                        // The first violation is the refusal: no further work for a hostile
                        // document.
                        SchemaValidatorsConfig.builder().failFast(true).build());
    }

    private static Set<String> knownNames() {
        final Set<String> names = new HashSet<>();
        for (final String name :
                List.of("review-profile-1.schema.json", "review-profile-2.schema.json")) {
            collectNames(schemaNode(name), names);
        }
        return Set.copyOf(names);
    }

    private static void collectNames(final JsonNode schema, final Set<String> names) {
        if (schema.isObject()) {
            final JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(names::add);
            }
        }
        for (final JsonNode child : schema) {
            collectNames(child, names);
        }
    }
}
