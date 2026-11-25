package org.fuin.porelo.build.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.contrastsecurity.sarif.SarifSchema210;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.review.SarifRefused.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SarifReadingTest {

    private static final String REVIEW = "/org/fuin/porelo/build/api/review/";

    // Every hostile document carries it where it is hostile; no refusal may repeat it.
    private static final String MARKER = "MARKER-5e1f";

    private static final String REPLY = "\"porelo/replyTo\": \"1834527690\"";

    private static final int MIB = 1024 * 1024;

    // A quarter of a default thread's stack on Linux: enough for the reader's own frames.
    private static final long SMALL_STACK = 256 * 1024;

    @ParameterizedTest
    @CsvSource({
        "deep.sarif.json, NESTING, /runs/0/results/0/properties/porelo~1replyTo/0/0/0/0/0/0/0/0/0/0/0/0/0/0/0/0/0/0/0",
        "duplicate-key.sarif.json, DUPLICATE_KEY, /runs/0/results/0/properties",
        "trailing-token.sarif.json, TRAILING_TOKENS, ''",
        "comment.sarif.json, JSON_SYNTAX, /runs/0/results/0/kind",
        "nan.sarif.json, JSON_SYNTAX, /runs/0/results/0/locations/0/physicalLocation/region/startLine",
        "single-quote.sarif.json, JSON_SYNTAX, /runs/0/results/0/properties/porelo~1replyTo",
        "leading-zero.sarif.json, JSON_SYNTAX, /runs/0/results/0/locations/0/physicalLocation/region/startLine",
        "unknown-field.sarif.json, SCHEMA, ''",
        "long-key.sarif.json, NAME_LENGTH, /runs/0/results/0/properties",
        "long-number.sarif.json, NUMBER_LENGTH, /runs/0/results/0/locations/0/physicalLocation/region/startLine"
    })
    void aHostileFixtureIsRefusedByItsRuleAtItsPath(
            final String fixture, final Rule rule, final String path) throws IOException {
        final SarifRefused refused = refused(resource("hostile/" + fixture));
        assertThat(refused.rule()).as("the rule %s is refused by", fixture).isEqualTo(rule);
        assertThat(refused.path()).as("where %s is refused", fixture).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "forge-page.sarif.json",
                "submitted-comment.sarif.json",
                "submitted-reply.sarif.json"
            })
    void eachProfileTwoFixtureIsRead(final String fixture) throws Exception {
        final byte[] document = resource("fixtures/" + fixture);
        final SarifSchema210 sarif = SarifReading.read(document);
        assertThat(sarif.getRuns()).as("%s's runs", fixture).hasSize(1);
        assertThat(sarif.getProperties().getAdditionalProperties())
                .as("%s's profile", fixture)
                .containsEntry("porelo/profileVersion", "2");
        assertThat(sarif.getRuns().get(0).getResults())
                .as("%s's results", fixture)
                .hasSize(new String(document, StandardCharsets.UTF_8).split("\"kind\"").length - 1);
    }

    @Test
    void eachProfileOneFixtureIsRead() throws Exception {
        final List<Path> fixtures;
        // Porelo writes profile 1 in the gate; its fixtures are read where they lie, never copied.
        try (Stream<Path> files =
                Files.list(
                        Path.of(
                                "../../gate/src/test/resources/org/fuin/porelo/gate/review/fixtures"))) {
            fixtures = files.filter(f -> f.toString().endsWith(".sarif.json")).sorted().toList();
        }
        assertThat(fixtures).as("profile 1's fixtures").isNotEmpty();
        for (final Path fixture : fixtures) {
            final SarifSchema210 sarif = SarifReading.read(Files.readAllBytes(fixture));
            assertThat(sarif.getProperties().getAdditionalProperties())
                    .as("%s's profile", fixture.getFileName())
                    .containsEntry("porelo/profileVersion", "1");
            assertThat(sarif.getRuns().get(0).getResults())
                    .as("%s's results", fixture.getFileName())
                    .isNotEmpty();
        }
    }

    @Test
    void aDocumentOfOneMibIsReadAndOneByteMoreIsRefusedBeforeItIsParsed() throws Exception {
        final String reply = reply();
        final String exact = reply + " ".repeat(MIB - reply.length());
        assertThat(SarifReading.read(utf8(exact)).getRuns())
                .as("a document of exactly 1 MiB")
                .hasSize(1);
        // Broken JSON past the limit: refused for its size, so it was never parsed.
        final SarifRefused refused = refused(utf8("{\"" + MARKER + "\": [" + "x".repeat(MIB)));
        assertThat(refused.rule()).as("1 MiB and more").isEqualTo(Rule.DOCUMENT_SIZE);
        assertThat(refused(utf8(exact + " ")).rule())
                .as("1 MiB and one byte")
                .isEqualTo(Rule.DOCUMENT_SIZE);
    }

    @Test
    void aNestingOf24LevelsPassesTheBoundAnd25AreRefused() throws Exception {
        final String deep = new String(resource("hostile/deep.sarif.json"), StandardCharsets.UTF_8);
        final String level24 = deep.replaceFirst("\\[\\[", "[").replaceFirst("]]", "]");
        assertThat(refused(utf8(level24)).rule())
                .as("24 levels, refused for its shape alone")
                .isEqualTo(Rule.SCHEMA);
        assertThat(refused(utf8(deep)).rule()).as("25 levels").isEqualTo(Rule.NESTING);
    }

    @Test
    void aHundredResultsAreReadAndTheHundredAndFirstIsRefused() throws Exception {
        assertThat(SarifReading.read(utf8(withResults(100))).getRuns().get(0).getResults())
                .as("100 results")
                .hasSize(100);
        final SarifRefused refused = refused(utf8(withResults(101)));
        assertThat(refused.rule()).as("101 results").isEqualTo(Rule.RESULTS);
        assertThat(refused.path())
                .as("where 101 results are refused")
                .isEqualTo("/runs/0/results/100");
    }

    @Test
    void aStringPastItsBoundIsRefusedAndAMessageTextHasTheWrittenFormsBound() throws Exception {
        final String url = "https://github.com/" + "x".repeat(16384 - 19);
        assertThat(
                        SarifReading.read(
                                        utf8(
                                                reply().replace(
                                                                "https://github.com/shop/app/pull/42",
                                                                url)))
                                .getRuns())
                .as("a string of 16384 characters")
                .hasSize(1);

        final SarifRefused refused =
                refused(
                        utf8(
                                reply().replace(
                                                REPLY,
                                                "\"porelo/replyTo\": \""
                                                        + MARKER
                                                        + "x".repeat(16384)
                                                        + "\"")));
        assertThat(refused.rule())
                .as("a string of more than 16384 characters")
                .isEqualTo(Rule.STRING_LENGTH);
        assertThat(refused.path())
                .as("where a long string is refused")
                .isEqualTo("/runs/0/results/0/properties/porelo~1replyTo");

        final SarifRefused underUnknownNames =
                refused(
                        utf8(
                                reply().replace(
                                                REPLY,
                                                REPLY
                                                        + ", \"porelo/"
                                                        + MARKER
                                                        + "\": {\"text\": \""
                                                        + "x".repeat(16385)
                                                        + "\"}")));
        assertThat(underUnknownNames.path())
                .as("a path through names no profile knows, written without them")
                .isEqualTo("/runs/0/results/0/properties/*/text");
    }

    @Test
    void aMessageTextMayPassTheStringBoundInProfileOneAloneWhereverTheProfileIsNamed()
            throws Exception {
        // Escaped braces double a text as written; profile 1 bounds that form at 32768.
        final String text = "{{".repeat(10000);
        assertThat(SarifReading.read(utf8(profileOne(text, false))).getRuns())
                .as("a profile 1 message text of 20000 characters as written")
                .hasSize(1);
        assertThat(SarifReading.read(utf8(profileOne(text, true))).getRuns())
                .as("the same, the profile named after the runs")
                .hasSize(1);

        final SarifRefused refused = refused(utf8(withText(MARKER + text)));
        assertThat(refused.rule())
                .as("a profile 2 message text of 20000 characters as written")
                .isEqualTo(Rule.STRING_LENGTH);
        assertThat(refused.path())
                .as("where a long message text is refused")
                .isEqualTo("/runs/0/results/0/message/text");
        assertThat(SarifReading.read(utf8(withText("{{".repeat(8192)))).getRuns())
                .as("a profile 2 message text of 16384 characters as written")
                .hasSize(1);

        final String unnamed =
                withText(MARKER + text).replace("\"porelo/profileVersion\": \"2\", ", "");
        assertThat(refused(utf8(unnamed)).rule())
                .as("a long message text in a document naming no profile")
                .isEqualTo(Rule.STRING_LENGTH);
    }

    @Test
    void aResultOfMoreThan32KibIsRefused() {
        final SarifRefused refused = refused(utf8(withText(MARKER + "{{".repeat(16370))));
        assertThat(refused.rule()).as("a result of more than 32 KiB").isEqualTo(Rule.RESULT_SIZE);
        assertThat(refused.path())
                .as("where a large result is refused")
                .isEqualTo("/runs/0/results/0");
    }

    @Test
    void moreThan100000TokensAreRefused() {
        final String many = "\"" + MARKER + "\"" + ",0".repeat(100_000);
        final SarifRefused refused =
                refused(
                        utf8(
                                reply().replace(
                                                "\"porelo/baseRevisionId\"",
                                                "\"porelo/push\": ["
                                                        + many
                                                        + "], \"porelo/baseRevisionId\"")));
        assertThat(refused.rule()).as("more than 100000 tokens").isEqualTo(Rule.TOKENS);
        assertThat(refused.path())
                .as("where the tokens run out")
                .startsWith("/runs/0/properties/porelo~1push/");
    }

    @Test
    void aNumberOf20CharactersPassesTheBoundAndIsRefusedOnlyByTheModel() {
        final SarifRefused refused =
                refused(
                        utf8(
                                reply().replace(
                                                "\"startLine\": 41",
                                                "\"startLine\": 12345678901234567890")
                                        .replace("1834527690", MARKER)));
        assertThat(refused.rule()).as("a line number no int holds").isEqualTo(Rule.BINDING);
        assertThat(refused.path())
                .as("where the model refuses it")
                .isEqualTo("/runs/0/results/0/locations/0/physicalLocation/region/startLine");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "\"kind\": \"review\" | \"kind\": 1",
                "\"kind\": \"review\" | \"kind\": \"MARKER-5e1f\"",
                "\"startLine\": 41 | \"startLine\": \"41\"",
                "\"level\": \"none\" | \"level\": [\"none\"]",
                "\"porelo/anchorKind\": \"line\" | \"porelo/anchorKind\": \"line\", \"porelo/threadState\": \"open\"",
                "\"porelo/anchorKind\": \"line\" | \"porelo/anchorKind\": \"line\", \"@class\": \"java.lang.ProcessBuilder\"",
                "\"kind\": \"review\" | \"kind\": \"review\", \"MARKER-5e1f\": {}"
            })
    void aDocumentTheProfileDoesNotAllowIsRefusedByItsSchema(final String from, final String to) {
        final SarifRefused refused = refused(utf8(reply().replace(from, to)));
        assertThat(refused.rule()).as("%s in place of %s", to, from).isEqualTo(Rule.SCHEMA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"3\"", "2", "\"MARKER-5e1f\""})
    void aDocumentNamingNoKnownProfileIsRefused(final String version) {
        final SarifRefused refused =
                refused(
                        utf8(
                                reply().replace(
                                                "\"porelo/profileVersion\": \"2\"",
                                                "\"porelo/profileVersion\": " + version)));
        assertThat(refused.rule())
                .as("profile version %s", version)
                .isEqualTo(Rule.PROFILE_VERSION);
        assertThat(refused.path())
                .as("where the profile is named")
                .isEqualTo("/properties/porelo~1profileVersion");
    }

    @Test
    void aDocumentThatIsNotUtf8IsRefused() {
        final String reply = reply().replace("1834527690", MARKER);
        assertThat(refused(reply.getBytes(StandardCharsets.UTF_16BE)).rule())
                .as("UTF-16")
                .isEqualTo(Rule.ENCODING);
        final byte[] bom = new byte[reply.length() + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(utf8(reply), 0, bom, 3, reply.length());
        assertThat(refused(bom).rule()).as("a byte order mark").isEqualTo(Rule.ENCODING);
        final byte[] broken = utf8(reply);
        broken[reply.indexOf(MARKER)] = (byte) 0xC3;
        broken[reply.indexOf(MARKER) + 1] = (byte) 0x28;
        assertThat(refused(broken).rule()).as("a broken UTF-8 sequence").isEqualTo(Rule.ENCODING);
    }

    @Test
    void readingADocumentPrintsNothingOnStandardError() throws Exception {
        // Logging binds once per JVM, so the read runs in one that has done nothing else.
        final Path fixture =
                Path.of(
                        "src/test/resources/org/fuin/porelo/build/api/review/fixtures/forge-page.sarif.json");
        final Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ReadOnce.class.getName(),
                                fixture.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        final String err =
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("the read's exit code, with %s", err).isZero();
        assertThat(err).as("what the read printed on standard error").isEmpty();
    }

    @Test
    void anArtifactUriOf2048CharactersIsReadAndOneMoreIsRefusedBeforeTheSchema() throws Exception {
        final String path = "src/main/java/org/example/shop/Login.java";
        assertThat(SarifReading.read(utf8(reply().replace(path, "a".repeat(2048)))).getRuns())
                .as("a profile 2 URI of 2048 characters")
                .hasSize(1);
        // A new platform thread has the JVM's default stack, unlike the test's main thread.
        final java.util.concurrent.atomic.AtomicReference<Object> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Thread reader =
                new Thread(
                        () -> {
                            try {
                                outcome.set(
                                        SarifReading.read(utf8(profileOneUri("a".repeat(2048))))
                                                .getRuns()
                                                .size());
                            } catch (final Throwable e) {
                                outcome.set(e.getClass().getName());
                            }
                        });
        reader.start();
        reader.join();
        assertThat(outcome.get())
                .as("a profile 1 URI of 2048 characters, read on a thread of the default stack")
                .isEqualTo(1);
        for (final String document :
                List.of(
                        reply().replace(path, MARKER + "a".repeat(2049 - MARKER.length())),
                        profileOneUri(MARKER + "a".repeat(2049 - MARKER.length())))) {
            final SarifRefused refused = refused(utf8(document));
            assertThat(refused.rule()).as("a URI of 2049 characters").isEqualTo(Rule.URI_LENGTH);
            assertThat(refused.path())
                    .as("where a long URI is refused")
                    .isEqualTo(
                            "/runs/0/results/0/locations/0/physicalLocation/artifactLocation/uri");
        }
        // Far past the bound, as the schema's pattern would have recursed through it.
        assertThat(refused(utf8(profileOneUri("a".repeat(12_000)))).rule())
                .as("a profile 1 URI of 12000 characters")
                .isEqualTo(Rule.URI_LENGTH);
    }

    @Test
    void anArtifactUriOf2048CharactersInItsCostliestShapesIsJudgedOnASmallStack() throws Exception {
        final String path = "src/main/java/org/example/shop/Login.java";
        final List<String> valid =
                List.of(
                        "a".repeat(2048),
                        "%41".repeat(682) + "aa",
                        "a/".repeat(1023) + "aa",
                        "a%41/".repeat(408) + "a%41");
        for (final String uri : valid) {
            assertThat(uri.length()).as("a costly URI's length").isLessThanOrEqualTo(2048);
            assertThat(onSmallStack(profileOneUri(uri)))
                    .as("a profile 1 URI of %s, read on a small stack", shape(uri))
                    .isEqualTo("read");
            assertThat(onSmallStack(reply().replace(path, uri)))
                    .as("a profile 2 URI of %s, read on a small stack", shape(uri))
                    .isEqualTo("read");
        }
        // Each fails only at its last character (the last a backslash escaped for JSON).
        for (final String uri :
                List.of(
                        "%41".repeat(682) + "a%",
                        "a/".repeat(1023) + "a/",
                        "a".repeat(2047) + "\\\\")) {
            assertThat(onSmallStack(profileOneUri(uri)))
                    .as("a profile 1 URI of %s failing at its end, on a small stack", shape(uri))
                    .isEqualTo("SCHEMA");
            assertThat(onSmallStack(reply().replace(path, uri)))
                    .as("a profile 2 URI of %s failing at its end, on a small stack", shape(uri))
                    .isEqualTo("SCHEMA");
        }
    }

    @Test
    void profileOnesStackSafeUriPatternAdmitsExactlyWhatItsGroupedFormDid() throws Exception {
        // The grouped form recursed per character; it stays here as the language to keep.
        final java.util.regex.Pattern grouped =
                java.util.regex.Pattern.compile(
                        "^([A-Za-z0-9._~-]|%[0-9A-F]{2})+(/([A-Za-z0-9._~-]|%[0-9A-F]{2})+)*(?![\\s\\S])");
        final java.util.regex.Pattern safe =
                java.util.regex.Pattern.compile(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(resource("review-profile-1.schema.json"))
                                .at("/$defs/uri/pattern")
                                .asText());
        final String alphabet = "a%4F/f\n";
        List<String> level = List.of("");
        int compared = 0;
        for (int length = 1; length <= 6; length++) {
            final List<String> next = new java.util.ArrayList<>();
            for (final String prefix : level) {
                for (final char c : alphabet.toCharArray()) {
                    final String text = prefix + c;
                    next.add(text);
                    assertThat(safe.matcher(text).find())
                            .as("the stack-safe pattern on %s", text.replace("\n", "\\n"))
                            .isEqualTo(grouped.matcher(text).find());
                    compared++;
                }
            }
            level = next;
        }
        assertThat(compared).as("the texts compared").isEqualTo(137_256);
    }

    @Test
    void noSchemaPatternRecursesPerCharacter() throws Exception {
        final List<String> shapes =
                List.of("a", "%41", "a/", "0", "-", "a-", "a.", "a ", "/", "%", "ab:");
        for (final String profile : List.of("1", "2")) {
            final List<String> patterns = new java.util.ArrayList<>();
            patterns(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(resource("review-profile-" + profile + ".schema.json")),
                    patterns);
            assertThat(patterns).as("profile %s's patterns", profile).isNotEmpty();
            for (final String pattern : patterns) {
                final java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
                for (final String shape : shapes) {
                    for (final String end : List.of("", "\n", "!")) {
                        final String input = shape.repeat(16_384 / shape.length()) + end;
                        assertThat(onSmallStack(() -> compiled.matcher(input).find()))
                                .as("profile %s's pattern %s on %s", profile, pattern, shape(input))
                                .isNotEqualTo(StackOverflowError.class.getName());
                    }
                }
            }
        }
    }

    private static void patterns(
            final com.fasterxml.jackson.databind.JsonNode node, final List<String> into) {
        node.properties()
                .forEach(
                        entry -> {
                            if ("pattern".equals(entry.getKey()) && entry.getValue().isTextual()) {
                                into.add(entry.getValue().asText());
                            } else {
                                patterns(entry.getValue(), into);
                            }
                        });
        if (node.isArray()) {
            node.forEach(each -> patterns(each, into));
        }
    }

    /**
     * Reads a document on a fresh thread of a small stack: read, its rule, or the error's class.
     */
    private static String onSmallStack(final String document) throws InterruptedException {
        return onSmallStack(
                () -> {
                    try {
                        SarifReading.read(utf8(document));
                        return "read";
                    } catch (final SarifRefused refused) {
                        return refused.rule().name();
                    }
                });
    }

    /**
     * Runs a call on a fresh thread whose stack is far smaller than a default thread's, so a
     * pattern that recurses per character overflows it whether or not the JIT has compiled it.
     */
    private static String onSmallStack(final java.util.concurrent.Callable<Object> call)
            throws InterruptedException {
        final java.util.concurrent.atomic.AtomicReference<String> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Thread thread =
                new Thread(
                        null,
                        () -> {
                            try {
                                outcome.set(String.valueOf(call.call()));
                            } catch (final Throwable e) {
                                outcome.set(e.getClass().getName());
                            }
                        },
                        "small-stack",
                        SMALL_STACK);
        thread.start();
        thread.join();
        return outcome.get();
    }

    private static String shape(final String text) {
        return text.length()
                + " characters starting "
                + text.substring(0, Math.min(6, text.length()));
    }

    private static String profileOneUri(final String uri) throws IOException {
        return Files.readString(
                        Path.of(
                                "../../gate/src/test/resources/org/fuin/porelo/gate/review/fixtures/comment-with-fix.sarif.json"))
                .replace("src/main/java/Login.java", uri);
    }

    @Test
    void theSchemaStopsAtItsFirstViolation() {
        final String runs =
                "\"runs\": [" + String.join(",", java.util.Collections.nCopies(40_000, "{}")) + "]";
        final String page =
                "{\"version\": \"2.1.0\", \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\","
                        + " \"properties\": {\"porelo/profileVersion\": \"2\", \"porelo/documentKind\": \"page\"}, "
                        + runs
                        + "}";
        final long start = System.nanoTime();
        final SarifRefused refused = refused(utf8(page));
        final long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(refused.rule()).as("40000 empty runs").isEqualTo(Rule.SCHEMA);
        assertThat(millis).as("milliseconds to refuse 40000 empty runs").isLessThan(5_000);
    }

    private static SarifRefused refused(final byte[] document) {
        final SarifRefused refused =
                catchThrowableOfType(SarifRefused.class, () -> SarifReading.read(document));
        assertThat(refused).as("a refusal").isNotNull();
        assertThat(String.valueOf(refused.getMessage()) + refused.path() + refused)
                .as("the refusal")
                .doesNotContain(MARKER)
                .doesNotContain("5e1f");
        assertThat(refused.getCause())
                .as("the refusal's cause, which could carry the text")
                .isNull();
        return refused;
    }

    private static String reply() {
        try {
            return new String(
                    resource("fixtures/submitted-reply.sarif.json"), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The submitted reply with its one result repeated to the count given. */
    private static String withResults(final int count) {
        final String reply = reply();
        final int start = reply.indexOf("        {\n          \"kind\"");
        final int end = reply.indexOf("\n      ]\n    }\n  ]");
        final String result = reply.substring(start, end);
        return reply.substring(0, start)
                + String.join(",\n", java.util.Collections.nCopies(count, result))
                + reply.substring(end);
    }

    /** Porelo's profile 1 comment with the text given, its profile named first or last. */
    private static String profileOne(final String text, final boolean profileLast)
            throws IOException {
        final String profile = "\n  \"properties\": { \"porelo/profileVersion\": \"1\" },";
        String doc =
                Files.readString(
                        Path.of(
                                "../../gate/src/test/resources/org/fuin/porelo/gate/review/fixtures/comment-with-fix.sarif.json"));
        final int start = doc.indexOf("\"text\": \"") + "\"text\": \"".length();
        doc = doc.substring(0, start) + text + doc.substring(doc.indexOf('"', start));
        if (profileLast) {
            assertThat(doc).as("the fixture's root properties").contains(profile);
            doc = doc.replace(profile, "");
            final int end = doc.lastIndexOf('}');
            doc =
                    doc.substring(0, end)
                            + ","
                            + profile.substring(0, profile.length() - 1)
                            + "\n}"
                            + doc.substring(end + 1);
        }
        return doc;
    }

    private static String withText(final String text) {
        final String reply = reply();
        final int start = reply.indexOf("\"text\": \"") + "\"text\": \"".length();
        return reply.substring(0, start) + text + reply.substring(reply.indexOf('"', start));
    }

    private static byte[] utf8(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(final String name) throws IOException {
        try (InputStream in = SarifReadingTest.class.getResourceAsStream(REVIEW + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }
}
