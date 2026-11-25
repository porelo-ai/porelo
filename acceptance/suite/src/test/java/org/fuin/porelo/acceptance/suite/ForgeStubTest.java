package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/** The stub forge adapter's file formats, as the scenarios write and read them. */
class ForgeStubTest {

    private static final String URL = "https://review.stub.invalid/shop/app/pull/42";

    @Test
    void aCommentsGuidIsAVersion4UuidAsTheProfileAsksAndStaysTheSameForTheSameComment() {
        final java.util.UUID named =
                java.util.UUID.nameUUIDFromBytes(
                        "thread answers text".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(ForgeStub.version4(named).version()).as("version 4").isEqualTo(4);
        assertThat(ForgeStub.version4(named).variant()).as("the IETF variant").isEqualTo(2);
        assertThat(ForgeStub.version4(named))
                .as("the same comment, the same guid")
                .isEqualTo(ForgeStub.version4(named));
    }

    private static String page(String thread) {
        return Json.write(
                Map.of(
                        "runs",
                        List.of(
                                Map.of(
                                        "results",
                                        List.of(
                                                Map.of(
                                                        "properties",
                                                        Map.of("porelo/threadId", thread)))))));
    }

    @Test
    void aWriteLogLineIsACommentOrAReplyInTheThreadTheStubGaveIt() {
        final String comment =
                Json.write(
                        Map.of(
                                "url",
                                URL,
                                "thread",
                                "9000000000",
                                "guid",
                                "g-1",
                                "document",
                                Map.of(
                                        "runs",
                                        List.of(
                                                Map.of(
                                                        "results",
                                                        List.of(
                                                                Map.of(
                                                                        "properties",
                                                                        Map.of())))))));
        final String reply =
                comment.replace(
                                "\"properties\":{}",
                                "\"properties\":{\"porelo/replyTo\":\"9100000001\"}")
                        .replace("9000000000", "9100000001");

        final List<ForgeStub.Written> written = ForgeStub.writeLog(comment + "\n" + reply + "\n");

        assertThat(written)
                .as("both writes, in order")
                .containsExactly(
                        new ForgeStub.Written(URL, "comment", "", "9000000000"),
                        new ForgeStub.Written(URL, "reply", "", "9100000001"));
        assertThat(ForgeStub.writeLog("")).as("an empty log").isEmpty();
    }

    @Test
    void aWriteLogLineNamesTheAccountItWentOutUnder() {
        final String line =
                Json.write(
                        Map.of(
                                "url",
                                URL,
                                "thread",
                                "9000000000",
                                "account",
                                "porelo-author",
                                "document",
                                Map.of(
                                        "runs",
                                        List.of(
                                                Map.of(
                                                        "results",
                                                        List.of(
                                                                Map.of(
                                                                        "properties",
                                                                        Map.of())))))));

        assertThat(ForgeStub.writeLog(line + "\n"))
                .as("the write, under its account")
                .containsExactly(
                        new ForgeStub.Written(URL, "comment", "porelo-author", "9000000000"));
    }

    @Test
    void aPullRequestsFactsNameItsServedRepositoryAndItsCommits() {
        final ForgeStub.Facts facts =
                new ForgeStub.Facts(
                        "https://127.0.0.1:4443/shop/app",
                        "a".repeat(40),
                        "b".repeat(40),
                        "b".repeat(40));

        final Map<?, ?> fresh =
                (Map<?, ?>)
                        Json.parse(ForgeStub.answersWithPullRequest("", URL, facts, "review-head"));
        assertThat(
                        Json.write(
                                ((Map<?, ?>) ((Map<?, ?>) fresh.get("reviews")).get(URL))
                                        .get("pages")))
                .as("a pull request nobody spoke on yet has one empty, complete page")
                .contains("\"porelo/documentKind\":\"page\"")
                .contains("\"porelo/completeness\":\"complete\"")
                .contains("\"results\":[]")
                .contains("\"revisionId\":\"" + "a".repeat(40) + "\"");

        final Map<?, ?> answers =
                (Map<?, ?>)
                        Json.parse(
                                ForgeStub.answersWithPullRequest(
                                        ForgeStub.answersWithPage("", URL, page("1")),
                                        URL,
                                        facts,
                                        "review-head"));

        assertThat(
                        (Map<?, ?>)
                                ((Map<?, ?>) ((Map<?, ?>) answers.get("reviews")).get(URL))
                                        .get("facts"))
                .as("the facts as the contract answers them")
                .isEqualTo(
                        Map.of(
                                "repository",
                                "https://127.0.0.1:4443/shop/app",
                                "head",
                                "a".repeat(40),
                                "base",
                                "b".repeat(40),
                                "mergeBase",
                                "b".repeat(40),
                                "state",
                                "open",
                                "approved",
                                false));
        assertThat(Json.write(((Map<?, ?>) answers.get("reviews")).get(URL)))
                .as("the conversation kept")
                .contains("\"1\"");
        assertThat((Map<?, ?>) answers.get("openPullRequests"))
                .as("open from its branch")
                .isEqualTo(Map.of("review-head", URL));
    }

    @Test
    void aTokenStandsForAnAccountByItsDigestAlone() throws Exception {
        final String token = "test-token-value";

        final String answers = ForgeStub.answersWithAccount("", token, "porelo-author");

        final String key =
                "sha256:"
                        + java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        token.getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        assertThat(answers).as("never the token").doesNotContain(token);
        final Map<?, ?> account =
                (Map<?, ?>)
                        ((Map<?, ?>) ((Map<?, ?>) Json.parse(answers)).get("accounts")).get(key);
        assertThat(account.get("porelo/forgeAccountName"))
                .as("the account the token stands for")
                .isEqualTo("porelo-author");
        assertThat(account.get("porelo/forgeAccountId")).as("with an id").isNotNull();
    }

    @Test
    void aPageAddedToAPullRequestJoinsItsLastPage() {
        final String once = ForgeStub.answersWithPage("", URL, page("1"));
        final String twice = ForgeStub.answersWithPage(once, URL, page("2"));

        final Object pages =
                ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(twice)).get("reviews")).get(URL))
                        .get("pages");
        assertThat((List<?>) pages).as("one page that grew").hasSize(1);
        assertThat(Json.write(pages)).as("with both results").contains("\"1\"").contains("\"2\"");
    }

    @Test
    void aMergedPullRequestNamesItsMergeCommit() {
        final String answers =
                Json.write(
                        Map.of(
                                "reviews",
                                Map.of(
                                        URL,
                                        Map.of(
                                                "facts",
                                                Map.of("head", "a".repeat(40), "state", "open")))));

        final String merged =
                ForgeStub.answersWithState(
                        answers,
                        URL,
                        "merged",
                        java.time.Instant.parse("2026-11-25T10:11:12.345Z"));
        final String closed =
                ForgeStub.answersWithState(
                        answers, URL, "closed", java.time.Instant.parse("2026-11-25T10:11:12Z"));
        final String reopened =
                ForgeStub.answersWithState(
                        closed, URL, "open", java.time.Instant.parse("2026-11-25T10:11:12Z"));

        assertThat(closed)
                .as("the closed facts, with when")
                .contains("\"decidedAt\":\"2026-11-25T10:11:12Z\"");
        assertThat(reopened).as("an open one was decided never").doesNotContain("decidedAt");
        assertThat(merged)
                .as("the merged facts")
                .contains("\"decidedAt\":\"2026-11-25T10:11:12Z\"")
                .contains("\"state\":\"merged\"")
                .contains("\"mergeCommit\":\"" + "a".repeat(40) + "\"")
                .contains("\"mergedHead\":\"main\"");
    }
}
