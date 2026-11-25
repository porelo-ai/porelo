package org.fuin.porelo.build.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.fuin.porelo.build.api.BuildProtocol;
import org.fuin.porelo.build.api.BuildServer;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.InstalledReviewReader;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubReviewTest {

    private static final String TOKEN = "vault-token-7f3a";

    private static final ForgeAccess ACCESS = new ForgeAccess("", TOKEN);

    private static final String PR = "https://forge.example/shop/app/pull/42";

    private static final String HEAD = "4e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d";

    private static final String BASE = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

    private static final String THREAD = "1834527690";

    private static final String ANSWERS =
            """
            {"token": "%s",
             "heads": {"porelo/fix-login": "%s"},
             "openPullRequests": {"porelo/fix-login": "%s"},
             "reviews": {"%s": {
               "facts": {"repository": "https://forge.example/shop/app", "head": "%s", "base": "%s",
                         "mergeBase": "%s", "state": "open", "approved": false},
               "pages": [%s]%s}}}
            """;

    private static final String PAGE =
            """
            {"version": "2.1.0", "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
             "properties": {"porelo/profileVersion": "2", "porelo/documentKind": "page"},
             "runs": [{"tool": {"driver": {"name": "Porelo"}},
               "versionControlProvenance": [{"repositoryUri": "https://forge.example/shop/app", "revisionId": "%s"}],
               "columnKind": "unicodeCodePoints",
               "properties": {"porelo/pullRequest": "%s", "porelo/baseRevisionId": "%s",
                              "porelo/completeness": "complete"},
               "results": [%s]}]}
            """;

    private static final String RESULT =
            """
            {"guid": "%s", "kind": "review", "level": "none",
             "message": {"text": "%s"},
             "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Login.java"},
                                                 "region": {"startLine": 41, "endLine": 43}}}],
             "properties": {"porelo/anchorKind": "line", "porelo/diffSide": "new",
                            "porelo/threadId": "%s", "porelo/threadState": "resolved",
                            "porelo/sourceKind": "forge-account", "porelo/forgeAccountId": "583231",
                            "porelo/forgeAccountName": "octocat"}}
            """;

    private static final JsonSchema PROFILE_2 = profile2();

    @TempDir Path directory;

    @Test
    void aPageAndAPostRoundTripThroughTheStubOverItsSocket() throws IOException {
        final Path answers = answers();
        try (InstalledReviewReader reader =
                new InstalledReviewReader(stub(answers), StubReader.FORGE, socket())) {

            assertThat(reader.pullRequest(ACCESS, PR))
                    .as("the facts the answers hold")
                    .isEqualTo(
                            new ReviewFacts(
                                    "https://forge.example/shop/app",
                                    HEAD,
                                    BASE,
                                    BASE,
                                    ReviewFacts.State.OPEN,
                                    false,
                                    null,
                                    null));
            assertThat(
                            reader.pullRequestOf(
                                    ACCESS, "git@forge.example:shop/app.git", "porelo/fix-login"))
                    .as("the open pull request from the branch")
                    .isEqualTo(PR);
            assertThat(valid(reader.comments(ACCESS, PR, null)))
                    .as("the conversation as the answers hold it")
                    .hasSize(1);

            assertThat(reader.post(ACCESS, PR, submission(THREAD, "Moved the check up.")))
                    .as("a reply answers its thread")
                    .isEqualTo(THREAD);
            final String opened = reader.post(ACCESS, PR, submission(null, "Refuse a blank one."));
            assertThat(opened).as("a new comment opens a thread of its own").isNotEqualTo(THREAD);

            final ConversationPage page = reader.comments(ACCESS, PR, null);
            assertThat(page.cursor()).as("one page, the last").isNull();
            final List<?> results = valid(page);
            assertThat(results).as("each post joined the conversation").hasSize(3);
            assertThat(properties(results.get(1)))
                    .as("the reply, in its thread and its thread's state, as the stub's account")
                    .containsEntry("porelo/threadId", THREAD)
                    .containsEntry("porelo/replyTo", THREAD)
                    .containsEntry("porelo/threadState", "resolved")
                    .containsEntry("porelo/sourceKind", "forge-account")
                    .containsEntry("porelo/forgeAccountId", StubReader.ACCOUNT_ID)
                    .containsEntry("porelo/forgeAccountName", StubReader.ACCOUNT_NAME);
            assertThat(properties(results.get(2)))
                    .as("the new comment, in the thread it opened")
                    .containsEntry("porelo/threadId", opened)
                    .containsEntry("porelo/threadState", "open");
            assertThat(((Map<?, ?>) results.get(2)).get("guid"))
                    .as("a page result carries a guid")
                    .isNotNull();
        }
    }

    @Test
    void writesEachPostToItsLogWithoutTheToken() throws IOException {
        final StubReader stub = new StubReader(answers());

        stub.post(ACCESS, PR, text(submission(THREAD, "Moved the check up.")));

        final String log = Files.readString(stub.posts(), StandardCharsets.UTF_8);
        assertThat(log)
                .as("the post is logged with its address and thread")
                .contains(PR)
                .contains(THREAD)
                .contains("Moved the check up.");
        assertThat(log).as("never the token").doesNotContain(TOKEN);
    }

    @Test
    void refusesAReplyToAThreadTheConversationDoesNotHoldAndASubmissionOfTwoResults()
            throws IOException {
        final StubReader stub = new StubReader(answers());

        assertThatThrownBy(() -> stub.post(ACCESS, PR, text(submission("77", "Lost."))))
                .as("a reply to no thread of the conversation")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.NO_SUCH_THREAD));
        assertThatThrownBy(
                        () ->
                                stub.post(
                                        ACCESS,
                                        PR,
                                        "{\"runs\": [{\"results\": [{\"kind\": \"review\"},"
                                                + " {\"kind\": \"review\"}]}]}"))
                .as("a submission of two results")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.NOT_IN_PROFILE));
        assertThat(stub.posts()).as("nothing refused was logged").doesNotExist();
    }

    @Test
    void refusesAsItsFileSaysAndOnlyTheTokenItNames() throws IOException {
        final StubReader stub = new StubReader(answers());

        assertThatThrownBy(() -> stub.comments(new ForgeAccess("", "another"), PR, null))
                .as("another token")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED));
        assertThatThrownBy(() -> stub.pullRequest(ACCESS, PR + "0"))
                .as("a pull request the answers do not hold")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST));
        Files.writeString(
                directory.resolve("answers.json"),
                "{\"reviewRefuse\": {\"reason\": \"RATE_LIMITED\", \"retryAfter\": 7}}");
        assertThatThrownBy(() -> stub.comments(ACCESS, PR, null))
                .as("a rate limit the file names, read again on this call")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(ForgeReviewRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds()).as("retry after").isEqualTo(7);
                        });
    }

    @Test
    void aPostedResultKeepsItsGuidOnEveryPageAndItsNumbersStayWhole() throws IOException {
        final StubReader stub = new StubReader(answers());
        stub.post(ACCESS, PR, text(submission(null, "Refuse a blank one.")));

        final ConversationPage first = stub.comments(ACCESS, PR, null);
        final ConversationPage again = stub.comments(ACCESS, PR, null);

        assertThat(((Map<?, ?>) valid(again).get(1)).get("guid"))
                .as("the same guid on every page written")
                .isEqualTo(((Map<?, ?>) valid(first).get(1)).get("guid"));
        assertThat(text(again.document()))
                .as("a line number written as the whole number it is")
                .contains("\"startLine\":41,")
                .doesNotContain("41.0");
    }

    @Test
    void aPostedResultIsWrittenAsTheAccountTheAnswersName() throws IOException {
        final StubReader stub =
                new StubReader(
                        answers(
                                ", \"postedAs\": {\"porelo/sourceKind\": \"forge-account\","
                                        + " \"porelo/forgeAccountId\": \"77\","
                                        + " \"porelo/forgeAccountName\": \"reviewer\"}",
                                page(result(THREAD, "Check the expiry first."))));
        stub.post(ACCESS, PR, text(submission(THREAD, "Moved the check up.")));

        assertThat(properties(valid(stub.comments(ACCESS, PR, null)).get(1)))
                .as("the account postedAs names")
                .containsEntry("porelo/forgeAccountId", "77")
                .containsEntry("porelo/forgeAccountName", "reviewer");
    }

    @Test
    void answersPageByPageAndRefusesACursorItDidNotGive() throws IOException {
        final StubReader stub =
                new StubReader(
                        answers(
                                "",
                                page(result(THREAD, "First page.")),
                                page(result("1834527691", "Second page."))));
        stub.post(ACCESS, PR, text(submission(THREAD, "A reply.")));

        final ConversationPage first = stub.comments(ACCESS, PR, null);
        assertThat(first.cursor()).as("the next page's cursor").isEqualTo("1");
        assertThat(valid(first)).as("the first page as written").hasSize(1);
        final ConversationPage last = stub.comments(ACCESS, PR, first.cursor());
        assertThat(last.cursor()).as("the last page").isNull();
        assertThat(valid(last)).as("the post joined the last page").hasSize(2);
        for (final String cursor : new String[] {"x", "9", "-1"}) {
            assertThatThrownBy(() -> stub.comments(ACCESS, PR, cursor))
                    .as("cursor %s", cursor)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aPostPastAHundredResultsSpillsOntoAPageOfItsOwn() throws IOException {
        final List<String> hundred = new ArrayList<>();
        for (int i = 0; i < ReviewProtocol.MOST_PAGE_RESULTS; i++) {
            hundred.add(result(Integer.toString(1_000 + i), "Comment " + i + "."));
        }
        final StubReader stub = new StubReader(answers("", page(String.join(",", hundred))));
        stub.post(ACCESS, PR, text(submission(null, "One more.")));

        final ConversationPage first = stub.comments(ACCESS, PR, null);
        assertThat(valid(first)).as("a full page").hasSize(ReviewProtocol.MOST_PAGE_RESULTS);
        assertThat(first.cursor()).as("and another after it").isEqualTo("1");
        assertThat(valid(stub.comments(ACCESS, PR, first.cursor())))
                .as("the post on its own page")
                .hasSize(1);
    }

    @Test
    void aPagePastOneMebibyteSpillsOntoTheNext() throws IOException {
        final List<String> large = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            large.add(result(Integer.toString(1_000 + i), "w".repeat(12_000)));
        }
        final StubReader stub = new StubReader(answers("", page(String.join(",", large))));
        stub.post(ACCESS, PR, text(submission(null, "One more.")));

        int results = 0;
        String cursor = null;
        do {
            final ConversationPage page = stub.comments(ACCESS, PR, cursor);
            assertThat(page.document().length)
                    .as("each page within the bound")
                    .isLessThanOrEqualTo(ReviewProtocol.MOST_PAGE_BYTES);
            results += valid(page).size();
            cursor = page.cursor();
        } while (cursor != null);
        assertThat(results).as("every result on one page or another").isEqualTo(91);
    }

    @Test
    void build2StillAnswersBesideTheReviewOnTheSameSocket() throws IOException {
        try (BuildServer server = new BuildServer(new StubReader(answers()), socket())) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(
                                client.call(
                                        BuildProtocol.HEAD,
                                        Map.of(
                                                "upstream", "git@forge.example:shop/app.git",
                                                "api", "",
                                                "token", TOKEN,
                                                "branch", "porelo/fix-login")))
                        .as("the build contract")
                        .isEqualTo(Map.of("commit", HEAD));
                assertThat(
                                client.call(
                                                ReviewProtocol.COMMENTS,
                                                Map.of("api", "", "token", TOKEN, "url", PR))
                                        .get("document"))
                        .as("the review contract, on the same connection")
                        .asString()
                        .contains(THREAD);
            }
        }
    }

    private Path answers() throws IOException {
        return answers("", page(result(THREAD, "Check the expiry first.")));
    }

    private Path answers(final String more, final String... pages) throws IOException {
        return Files.writeString(
                directory.resolve("answers.json"),
                ANSWERS.formatted(
                        TOKEN, HEAD, PR, PR, HEAD, BASE, BASE, String.join(",", pages), more),
                StandardCharsets.UTF_8);
    }

    private static String page(final String results) {
        return PAGE.formatted(HEAD, PR, BASE, results);
    }

    private static String result(final String thread, final String text) {
        return RESULT.formatted(UUID.randomUUID(), text, thread);
    }

    private static byte[] submission(final String replyTo, final String text) {
        final String reply = replyTo == null ? "" : ", \"porelo/replyTo\": \"" + replyTo + "\"";
        return ("""
                {"version": "2.1.0", "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
                 "properties": {"porelo/profileVersion": "2", "porelo/documentKind": "submission"},
                 "runs": [{"tool": {"driver": {"name": "Porelo"}},
                   "versionControlProvenance": [{"repositoryUri": "https://forge.example/shop/app",
                                                 "revisionId": "%s"}],
                   "columnKind": "unicodeCodePoints",
                   "properties": {"porelo/pullRequest": "%s", "porelo/baseRevisionId": "%s"},
                   "results": [{"kind": "review", "level": "none", "message": {"text": "%s"},
                   "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Login.java"},
                                                       "region": {"startLine": 41, "endLine": 43}}}],
                   "properties": {"porelo/anchorKind": "line", "porelo/diffSide": "new"%s}}]}]}
                """
                        .formatted(HEAD, PR, BASE, text, reply))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String text(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Holds a page to profile 2 and returns its results. */
    private static List<?> valid(final ConversationPage page) {
        assertThat(PROFILE_2.validate(text(page.document()), InputFormat.JSON))
                .as("the page holds to profile 2")
                .isEmpty();
        final Map<?, ?> document = (Map<?, ?>) Json.parse(text(page.document()));
        final Map<?, ?> run = (Map<?, ?>) ((List<?>) document.get("runs")).getFirst();
        return (List<?>) run.get("results");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(final Object result) {
        return (Map<String, Object>) ((Map<?, ?>) result).get("properties");
    }

    private static JsonSchema profile2() {
        try (InputStream in =
                StubReviewTest.class.getResourceAsStream(
                        "/org/fuin/porelo/build/api/review/review-profile-2.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Objects.requireNonNull(in, "profile 2's schema"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path socket() {
        return directory.resolve("r.sock");
    }

    private Path stub(final Path answers) throws IOException {
        final Path file = directory.resolve(StubReader.FORGE);
        Files.writeString(
                file,
                "#!/bin/sh\n"
                        + StubReader.ANSWERS
                        + "='"
                        + answers
                        + "' exec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + StubReader.class.getName()
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
