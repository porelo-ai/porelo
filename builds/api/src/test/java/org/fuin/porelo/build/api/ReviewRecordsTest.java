package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewRecordsTest {

    private static final String SHA = "a".repeat(40);

    private static final String DECIDED = "2026-11-20T10:15:30Z";

    @Test
    void factsAsTheContractAnswersThemAreRead() {
        assertThat(ReviewFacts.of(facts(Map.of())))
                .as("open, at a full commit, on an https repository")
                .isEqualTo(
                        new ReviewFacts(
                                "https://forge.example/o/n",
                                SHA,
                                SHA,
                                SHA,
                                ReviewFacts.State.OPEN,
                                false,
                                null,
                                null,
                                null,
                                null));
        assertThat(
                        ReviewFacts.of(
                                        facts(
                                                Map.of(
                                                        "state",
                                                        "merged",
                                                        "mergeCommit",
                                                        SHA,
                                                        "mergedHead",
                                                        "release/2.x")))
                                .mergedHead())
                .as("a branch with a slash")
                .isEqualTo("release/2.x");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "repository|http://forge.example/o/n",
                "repository|https://user:pass@forge.example/o/n",
                "repository|https://forge.example/n",
                "repository|https://forge.example/o/n?x=1",
                "repository|https://forge.example/o/..",
                "head|abc",
                "base|" + "A" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "state|pending",
                "approved|yes",
                "mergeCommit|" + "dddddddddddddddddddddddddddddddddddddddd"
            })
    void factsOutOfShapeAreRefused(final String field, final String value) {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of(field, value))))
                .as("%s %s", field, value)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "main\nstatus: ok",
                "main..x",
                "-main",
                "main.lock",
                ".hidden",
                "a//b",
                "a b",
                "x@{1}",
                "@",
                "end/",
                "end."
            })
    void aMergedBranchThatGitWouldRefuseIsRefused(final String branch) {
        assertThatThrownBy(
                        () ->
                                ReviewFacts.of(
                                        facts(
                                                Map.of(
                                                        "state",
                                                        "merged",
                                                        "mergeCommit",
                                                        SHA,
                                                        "mergedHead",
                                                        branch))))
                .as("%s is no branch", branch)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMergedPullRequestNamesItsMergeAndAnOpenOneNone() {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of("state", "merged"))))
                .as("merged without its merge")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ReviewFacts.of(
                                        facts(Map.of("mergeCommit", SHA, "mergedHead", "main"))))
                .as("open with a merge")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPageIsUtf8AndItsCursorBounded() {
        assertThatThrownBy(() -> new ConversationPage(new byte[] {(byte) 0xc3}, null))
                .as("bytes that are not UTF-8")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationPage.of("{}", "x".repeat(257)))
                .as("a cursor past 256 characters")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationPage.of("{}", "a\u0000b"))
                .as("a cursor with a control character")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationPage.of("{}", "x".repeat(256)).cursor())
                .as("a cursor of 256 characters")
                .hasSize(256);
    }

    @Test
    void aRefusalWithoutAReasonOrFieldSaysWhichIsMissing() {
        assertThatThrownBy(() -> ForgeReviewRefused.notInProfile(null))
                .as("no field")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("field");
        assertThatThrownBy(() -> new ForgeReviewRefused(null))
                .as("no reason")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason");
    }

    @ParameterizedTest
    @CsvSource({"merged,2026-11-20T10:15:30Z", "closed,2026-11-21T08:00:00.250Z"})
    void aMergedOrClosedPullRequestCarriesWhenTheForgeDecidedIt(
            final String state, final String time) {
        final Map<String, Object> changed =
                new LinkedHashMap<>(Map.of("state", state, "decidedAt", time));
        if ("merged".equals(state)) {
            changed.put("mergeCommit", SHA);
            changed.put("mergedHead", "main");
        }
        final ReviewFacts read = ReviewFacts.of(facts(changed));

        assertThat(read.decidedAt())
                .as("the time the forge answered")
                .isEqualTo(Instant.parse(time));
        assertThat(read.asMap())
                .as("written back as the contract writes it")
                .containsEntry("decidedAt", time);
    }

    @Test
    void anOpenPullRequestCarriesItsApprovalAndNoDecisionTime() {
        final ReviewFacts read = ReviewFacts.of(facts(Map.of("approved", Boolean.TRUE)));

        assertThat(read.approved()).as("the forge's observed verdict").isTrue();
        assertThat(read.decidedAt()).as("open, so not decided").isNull();
        assertThat(read.asMap()).as("no time on the wire").doesNotContainKey("decidedAt");
    }

    @Test
    void theAccountTheTokenStandsForIsReadAndWrittenBack() {
        final Map<String, Object> answer =
                facts(Map.of("account", Map.of("id", "583231", "login", "octocat")));

        final ReviewFacts read = ReviewFacts.of(answer);

        assertThat(read.account())
                .as("the forge account the token stands for")
                .isEqualTo(new ReviewFacts.Account("583231", "octocat"));
        assertThat(read.asMap().get("account"))
                .as("written back as the contract's ForgeAccount")
                .isEqualTo(Map.of("id", "583231", "login", "octocat"));
        assertThat(ReviewFacts.of(facts(Map.of())).account())
                .as("absent when the forge does not say")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id-blank", "id-long", "login-blank", "login-long", "login-line"})
    void anAccountOutOfProfileTwosBoundsIsRefused(final String which) {
        final String id =
                switch (which) {
                    case "id-blank" -> " ";
                    case "id-long" -> "1".repeat(65);
                    default -> "583231";
                };
        final String login =
                switch (which) {
                    case "login-blank" -> "";
                    case "login-long" -> "o".repeat(257);
                    case "login-line" -> "octo\ncat";
                    default -> "octocat";
                };

        assertThatThrownBy(
                        () ->
                                ReviewFacts.of(
                                        facts(Map.of("account", Map.of("id", id, "login", login)))))
                .as("%s is out of profile 2's bounds for a forge account", which)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account");
    }

    @Test
    void anAccountThatIsNoObjectIsRefused() {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of("account", "octocat"))))
                .as("the contract's ForgeAccount is an object of id and login")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account");
    }

    @Test
    void aDecisionTimeOnAnOpenPullRequestIsRefused() {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of("decidedAt", "2026-11-20T10:15:30Z"))))
                .as(
                        "an open pull request was neither merged nor closed, so it has no decision time")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedAt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"merged", "closed"})
    void aMergedOrClosedPullRequestWithoutItsDecisionTimeIsRefused(final String state) {
        final Map<String, Object> changed = new LinkedHashMap<>(Map.of("state", state));
        if ("merged".equals(state)) {
            changed.put("mergeCommit", SHA);
            changed.put("mergedHead", "main");
        }
        final Map<String, Object> answer = facts(changed);
        answer.remove("decidedAt");
        assertThatThrownBy(() -> ReviewFacts.of(answer))
                .as("a %s pull request's answer names when the forge decided it", state)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedAt");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "yesterday",
                "2026-11-20T10:15:30",
                "2026-11-20T10:15:30+01:00",
                "2026-11-20 10:15:30Z",
                "2026-13-20T10:15:30Z",
                "2026-11-20T10:15:30.Z"
            })
    void aDecisionTimeThatIsNoRfc3339UtcTimeIsRefused(final String time) {
        assertThatThrownBy(
                        () -> ReviewFacts.of(facts(Map.of("state", "closed", "decidedAt", time))))
                .as("%s is no RFC 3339 time in UTC", time)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedAt");
    }

    private static Map<String, Object> facts(final Map<String, Object> changed) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("repository", "https://forge.example/o/n");
        facts.put("head", SHA);
        facts.put("base", SHA);
        facts.put("mergeBase", SHA);
        facts.put("state", "open");
        facts.put("approved", Boolean.FALSE);
        facts.putAll(changed);
        if (!"open".equals(facts.get("state"))) {
            facts.putIfAbsent("decidedAt", DECIDED);
        }
        return facts;
    }
}
